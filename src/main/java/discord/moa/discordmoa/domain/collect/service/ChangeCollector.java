package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubCommit;
import discord.moa.discordmoa.github.dto.GitHubCommitPage;
import discord.moa.discordmoa.github.dto.GitHubCompare;
import discord.moa.discordmoa.github.dto.GitHubFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeCollector {

    static final int FORCE_PUSH_COMMIT_LIMIT = 20;
    static final int INITIAL_COMMIT_LIMIT = 100;
    static final int COMPARE_FILE_LIMIT = 300;
    static final int PATCH_MAX_LENGTH = 10_000;

    private static final Set<String> REWRITTEN_HISTORY_STATUSES = Set.of("diverged", "behind");

    private final GitHubClient gitHubClient;
    private final TrackedRepoRepository trackedRepoRepository;
    private final TeamRepository teamRepository;
    private final ChangeBatchWriter changeBatchWriter;

    public Optional<ChangeBatch> collect(TrackedRepo repo) {
        RepoRef ref = RepoRef.of(repo);

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

    public List<RepoCollectResult> collectTeam(Long teamId) {
        List<RepoCollectResult> results = new ArrayList<>();
        for (TrackedRepo repo : trackedRepoRepository.findAllByTeamId(teamId)) {
            try {
                results.add(collect(repo)
                        .map(batch -> RepoCollectResult.collected(repo.getFullName(), batch))
                        .orElseGet(() -> RepoCollectResult.noChange(repo.getFullName())));
            } catch (RuntimeException e) {
                log.error("레포 변경 수집 실패: repo={}", repo.getFullName(), e);
                results.add(RepoCollectResult.failed(repo.getFullName()));
            }
        }
        return results;
    }

    public TeamCollectResult collectTeamByChannel(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        return new TeamCollectResult(team.getName(), collectTeam(team.getId()));
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

    private record Collected(List<CommitSnapshot> commits, List<FileChange> files,
                             boolean forcePushDetected, boolean truncated) {
    }
}
