package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.service.TeamLocks;
import discord.moa.discordmoa.domain.team.service.TrackedRepoDeactivator;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.client.GitHubInstallationGoneException;
import discord.moa.discordmoa.github.dto.GitHubCommit;
import discord.moa.discordmoa.github.dto.GitHubCommitPage;
import discord.moa.discordmoa.github.dto.GitHubCompare;
import discord.moa.discordmoa.github.dto.GitHubFile;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeCollector {

    static final int FORCE_PUSH_COMMIT_LIMIT = 20;
    static final int INITIAL_COMMIT_LIMIT = 100;
    static final int COMPARE_FILE_LIMIT = 300;
    static final int PATCH_MAX_LENGTH = 10_000;

    static final String INSTALLATION_GONE = "App 설치가 해제됐어요 (레포 주인이 다시 설치해야 해요)";
    static final String REPO_NOT_ACCESSIBLE = "App이 이 레포에 접근할 수 없어요 (설치 설정에서 레포 선택이 빠졌을 수 있어요)";
    static final String GITHUB_UNREACHABLE = "GitHub가 응답하지 않아요 (잠시 후 다시 시도해 주세요)";
    static final String UNKNOWN_FAILURE = "수집 중 오류가 났어요 (서버 로그를 확인해 주세요)";
    static final String REPO_REPLACED =
            "GitHub에서 레포가 삭제됐거나 같은 이름으로 다시 만들어졌어요. 팀 채팅방에서 /레포등록을 다시 해주세요.";

    private static final Set<String> REWRITTEN_HISTORY_STATUSES = Set.of("diverged", "behind");

    private final GitHubClient gitHubClient;
    private final TrackedRepoRepository trackedRepoRepository;
    private final TeamRepository teamRepository;
    private final ChangeBatchWriter changeBatchWriter;
    private final TeamLocks teamLocks;
    private final TrackedRepoDeactivator deactivator;
    private final OpsNotifier opsNotifier;

    /**
     * App을 지웠다가 다시 설치해 설치 ID가 바뀌었으면, 새 ID로 갱신하고 한 번만 다시 시도한다.
     */
    public Optional<ChangeBatch> collect(TrackedRepo repo) {
        try {
            return collectOnce(repo);
        } catch (HttpClientErrorException.Unauthorized e) {
            // 설치가 해제되면 캐시돼 있던 설치 토큰이 401을 받는다. 토큰을 새로 받아 원인을 확인한다
            log.info("설치 토큰이 거절돼 새로 받아요: repo={}, installationId={}", repo.getFullName(), repo.getInstallationId());
            gitHubClient.forgetInstallationToken(repo.getInstallationId());
            try {
                return collectOnce(repo);
            } catch (GitHubInstallationGoneException gone) {
                return retryWithNewInstallation(repo, gone);
            }
        } catch (GitHubInstallationGoneException e) {
            return retryWithNewInstallation(repo, e);
        }
    }

    private Optional<ChangeBatch> retryWithNewInstallation(TrackedRepo repo, GitHubInstallationGoneException gone) {
        RepoRef ref = RepoRef.of(repo);
        Long newInstallationId = gitHubClient.findInstallationId(ref.owner(), ref.name())
                .filter(id -> !id.equals(repo.getInstallationId()))
                .orElseThrow(() -> gone);

        log.info("GitHub App이 다시 설치돼 설치 ID를 갱신해요: repo={}, {} -> {}",
                repo.getFullName(), repo.getInstallationId(), newInstallationId);
        trackedRepoRepository.updateInstallationId(repo.getId(), newInstallationId);
        repo.changeInstallationId(newInstallationId);
        return collectOnce(repo);
    }

    private Optional<ChangeBatch> collectOnce(TrackedRepo repo) {
        RepoRef ref = RepoRef.of(repo);
        verifySameRepo(repo, ref);

        Optional<String> head = gitHubClient.findHeadSha(ref.installationId(), ref.owner(), ref.name(), repo.getDefaultBranch());
        if (head.isEmpty()) {
            return Optional.empty();
        }

        String headSha = head.get();
        String baseSha = repo.getLastProcessedSha();
        if (headSha.equals(baseSha)) {
            return Optional.empty();
        }

        Collected collected = baseSha == null
                ? collectByCommits(ref, headSha, INITIAL_COMMIT_LIMIT, false)
                : collectByCompare(ref, repo.getFullName(), baseSha, headSha);

        if (collected.commits().isEmpty()) {
            return Optional.empty();
        }

        ChangeBatch batch = new ChangeBatch(repo, baseSha, headSha, Instant.now(),
                collected.forcePushDetected(), collected.truncated(), collected.commits(), collected.files());
        return Optional.of(changeBatchWriter.saveAndAdvance(batch));
    }

    /**
     * 수집은 owner/name으로 레포를 찾으므로, 레포가 삭제된 뒤 같은 이름으로 다시 만들어지면 옛 레코드가 새 레포를 가리킨다.
     * 그러면 옛 커서가 새 레포에 없어 compare가 404가 되고 force push로 오인해 새 레포 커밋을 옛 레코드로 수집하게 된다.
     * 그래서 수집 전에 레포 자체를 확인한다.
     * <ul>
     *   <li>레포 ID가 등록 때와 다름: 같은 이름으로 다시 만들어짐 → 비활성화 (자동 비활성화는 이 경우뿐)</li>
     *   <li>레포 404(빈 결과): 수집 실패(REPO_NOT_ACCESSIBLE)로만 처리한다. GitHub는 레포 삭제와
     *       "App 설치 설정에서 레포 선택이 빠짐"을 둘 다 404로 줘서 구분할 수 없다. 설정 실수로 비활성화하면
     *       미발송 배치가 버려지고 재등록 때 커서가 HEAD로 초기화돼 그 사이 커밋이 영구히 빠지므로,
     *       데이터를 잃지 않는 쪽(수집만 실패)을 택한다. compare까지 가지 않으니 force push로 오인하지도 않는다.</li>
     *   <li>네트워크 오류·5xx 등: 예외가 그대로 올라가 수집 실패로만 처리한다 (비활성화하지 않음)</li>
     * </ul>
     * 이 확인을 통과한 뒤의 compare 404는 레포가 아니라 base 커밋이 없는 것이므로 기존처럼 force push로 처리한다.
     */
    private void verifySameRepo(TrackedRepo repo, RepoRef ref) {
        Optional<GitHubRepo> current = gitHubClient.findRepo(ref.installationId(), ref.owner(), ref.name());
        if (current.isEmpty()) {
            throw new RepoNotAccessibleException();
        }
        if (!current.get().id().equals(repo.getGithubRepoId())) {
            log.warn("레포 ID가 등록 때와 달라요 (삭제 후 같은 이름으로 재생성): repo={}, 등록={}, 지금={}",
                    repo.getFullName(), repo.getGithubRepoId(), current.get().id());
            throw new RepoGoneException(REPO_REPLACED);
        }
    }

    public List<RepoCollectResult> collectTeam(Long teamId) {
        List<RepoCollectResult> results = new ArrayList<>();
        // 해제된(비활성) 레포는 수집하지 않는다
        for (TrackedRepo repo : trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(teamId)) {
            try {
                RepoCollectResult result = collect(repo)
                        .map(batch -> RepoCollectResult.collected(repo.getFullName(), batch))
                        .orElseGet(() -> RepoCollectResult.noChange(repo.getFullName()));
                // /레포목록의 "마지막 수집 시각": 변경이 없어도 GitHub 확인에 성공하면 갱신한다
                trackedRepoRepository.markCollected(repo.getId(), Instant.now());
                results.add(result);
            } catch (RepoGoneException e) {
                deactivateGone(teamId, repo, e.getMessage());
                results.add(RepoCollectResult.deactivated(repo.getFullName(), e.getMessage()));
            } catch (RuntimeException e) {
                String reason = describeFailure(e);
                if (reason.equals(UNKNOWN_FAILURE)) {
                    log.error("레포 변경 수집 실패: repo={}", repo.getFullName(), e);
                } else {
                    log.warn("레포 변경 수집 실패: repo={}, reason={}, error={}", repo.getFullName(), reason, e.toString());
                }
                results.add(RepoCollectResult.failed(repo.getFullName(), reason));
            }
        }
        return results;
    }

    private void deactivateGone(Long teamId, TrackedRepo repo, String reason) {
        log.warn("GitHub에서 레포를 찾을 수 없거나 다른 레포라 등록을 해제해요: repo={}, reason={}", repo.getFullName(), reason);
        OptionalInt discarded = deactivator.deactivate(repo.getId(), Instant.now());
        if (discarded.isEmpty()) {
            return;   // 그사이 다른 경로(/레포해제 등)에서 이미 비활성화됐다
        }
        String teamName = teamRepository.findById(teamId).map(Team::getName).orElse(null);
        StringBuilder detail = new StringBuilder("레포: ").append(repo.getFullName()).append("\n사유: ").append(reason);
        if (discarded.getAsInt() > 0) {
            detail.append("\n아직 보내지 않은 변경 ").append(discarded.getAsInt()).append("건은 보내지 않아요.");
        }
        opsNotifier.notify(new OpsEvent(OpsEvent.Type.REPO_DEACTIVATED, teamName, null, detail.toString()));
    }

    static String describeFailure(RuntimeException e) {
        if (e instanceof GitHubInstallationGoneException) {
            return INSTALLATION_GONE;
        }
        if (e instanceof HttpClientErrorException.NotFound || e instanceof RepoNotAccessibleException) {
            return REPO_NOT_ACCESSIBLE;
        }
        if (e instanceof ResourceAccessException) {
            return GITHUB_UNREACHABLE;
        }
        return UNKNOWN_FAILURE;
    }

    public TeamCollectResult collectTeamByChannel(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        // /수집테스트: 같은 팀의 준비·발송과 겹치지 않게 하고, 처리 중이면 기다리지 않고 거절한다
        return teamLocks.callIfFree(team.getId(), team.getName(),
                () -> new TeamCollectResult(team.getName(), collectTeam(team.getId())));
    }

    private Collected collectByCompare(RepoRef ref, String fullName, String baseSha, String headSha) {
        Optional<GitHubCompare> found = gitHubClient.compare(ref.installationId(), ref.owner(), ref.name(), baseSha, headSha);

        if (found.isEmpty() || REWRITTEN_HISTORY_STATUSES.contains(found.get().status())) {
            String reason = found.map(c -> "status=" + c.status()).orElse("base 커밋을 찾을 수 없음(404)");
            log.warn("force push 감지, 최근 커밋 {}개만 수집해요: repo={}, base={}, head={}, {}",
                    FORCE_PUSH_COMMIT_LIMIT, fullName, baseSha, headSha, reason);
            return collectByCommits(ref, headSha, FORCE_PUSH_COMMIT_LIMIT, true);
        }

        GitHubCompare compare = found.get();
        List<GitHubCommit> commits = nullToEmpty(compare.commits());
        List<GitHubFile> files = nullToEmpty(compare.files());
        boolean truncated = compare.totalCommits() > commits.size() || files.size() >= COMPARE_FILE_LIMIT;

        return new Collected(
                commits.stream().map(ChangeCollector::toSnapshot).toList(),
                files.stream().map(file -> toFileChange(null, file)).toList(),
                false,
                truncated
        );
    }

    private Collected collectByCommits(RepoRef ref, String headSha, int limit, boolean forcePushDetected) {
        GitHubCommitPage page = gitHubClient.listCommits(ref.installationId(), ref.owner(), ref.name(), headSha, limit);

        List<CommitSnapshot> commits = new ArrayList<>();
        List<FileChange> files = new ArrayList<>();
        for (GitHubCommit summary : page.commits().reversed()) {
            GitHubCommit detail = gitHubClient.findCommit(ref.installationId(), ref.owner(), ref.name(), summary.sha());
            commits.add(toSnapshot(detail));
            nullToEmpty(detail.files()).forEach(file -> files.add(toFileChange(detail.sha(), file)));
        }

        boolean truncated = !forcePushDetected && page.hasNext();
        return new Collected(commits, files, forcePushDetected, truncated);
    }

    private static CommitSnapshot toSnapshot(GitHubCommit commit) {
        GitHubCommit.Detail detail = commit.commit();
        GitHubCommit.Account account = commit.author();
        GitHubCommit.Signature author = detail.author();
        GitHubCommit.Signature committer = detail.committer();

        return new CommitSnapshot(
                commit.sha(),
                detail.message(),
                account == null ? null : account.login(),
                account == null ? null : account.id(),
                author == null ? null : author.name(),
                committer != null ? committer.date() : author == null ? null : author.date(),
                commit.htmlUrl()
        );
    }

    static FileChange toFileChange(String commitSha, GitHubFile file) {
        String patch = file.patch();
        boolean truncated = patch != null && patch.length() > PATCH_MAX_LENGTH;
        return new FileChange(commitSha, file.filename(), file.status(), file.additions(), file.deletions(),
                truncated ? cut(patch) : patch, truncated);
    }

    private static String cut(String patch) {
        int end = PATCH_MAX_LENGTH;
        if (Character.isHighSurrogate(patch.charAt(end - 1))) {
            end--;
        }
        return patch.substring(0, end);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record RepoRef(Long installationId, String owner, String name) {
        static RepoRef of(TrackedRepo repo) {
            String[] parts = repo.getFullName().split("/", 2);
            return new RepoRef(repo.getInstallationId(), parts[0], parts[1]);
        }
    }

    /**
     * GitHub의 같은 이름 레포가 등록 때와 다른 레포다 (삭제 후 재생성). 수집하지 않고 등록을 해제한다.
     */
    /**
     * 레포 조회가 404였다 (삭제됐거나 App 접근 권한에서 빠짐). 수집 실패로만 처리하고 등록은 유지한다.
     */
    private static final class RepoNotAccessibleException extends RuntimeException {
        RepoNotAccessibleException() {
            super("GitHub 레포 조회가 404예요");
        }
    }

    private static final class RepoGoneException extends RuntimeException {
        RepoGoneException(String reason) {
            super(reason);
        }
    }

    private record Collected(List<CommitSnapshot> commits, List<FileChange> files,
                             boolean forcePushDetected, boolean truncated) {
    }
}
