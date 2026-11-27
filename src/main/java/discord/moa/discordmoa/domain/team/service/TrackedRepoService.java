package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import discord.moa.discordmoa.github.dto.RepoAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class TrackedRepoService {

    static final String ADMIN_ONLY_DEACTIVATE = "레포 해제는 운영진에게 요청해 주세요.";


    static final int MAX_AUTOCOMPLETE_CHOICES = 25;   // Discord 자동완성 후보 최대 개수
    static final int MAX_CHOICE_LENGTH = 100;          // Discord 자동완성 후보 이름·값 최대 길이

    private static final String NOT_TEAM_CHANNEL =
            "팀 채팅방에서만 레포를 등록할 수 있어요. 이 채널이 팀으로 등록돼 있는지 운영진에게 확인해 주세요.";

    private final TrackedRepoRepository trackedRepoRepository;
    private final TeamRepository teamRepository;
    private final GitHubClient gitHubClient;
    private final OpsNotifier opsNotifier;
    private final TrackedRepoDeactivator deactivator;
    private final Clock clock;
    private final String installUrl;

    public TrackedRepoService(TrackedRepoRepository trackedRepoRepository,
                              TeamRepository teamRepository,
                              GitHubClient gitHubClient,
                              OpsNotifier opsNotifier,
                              TrackedRepoDeactivator deactivator,
                              Clock clock,
                              @Value("${github.install-url}") String installUrl) {
        this.trackedRepoRepository = trackedRepoRepository;
        this.teamRepository = teamRepository;
        this.gitHubClient = gitHubClient;
        this.opsNotifier = opsNotifier;
        this.deactivator = deactivator;
        this.clock = clock;
        this.installUrl = installUrl;
    }

    /**
     * - 같은 팀이 이미 등록한 활성 레포: 설치 ID만 갱신한다 (App 재설치 후 등).
     * - 해제된(비활성) 레포: 같은 팀이면 다시 활성화, 다른 팀이면 이 팀으로 옮긴다. 커서는 지금 최신 커밋으로 초기화하고,
     *   해제돼 있던 동안 남은 미발송 배치(해제 뒤 발송 실패로 되돌아온 것 등)는 버린다.
     * - 다른 팀의 활성 레포: 거절한다.
     * - 같은 팀에 이름은 같고 GitHub 레포 ID가 다른 활성 등록(삭제 후 재생성된 레포의 옛 등록)은 정리한다.
     */
    public RepoRegistration register(Long channelId, String url) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(NOT_TEAM_CHANNEL));

        RepoAddress address = RepoAddress.parse(url);

        Long installationId = gitHubClient.findInstallationId(address.owner(), address.repo())
                .orElseThrow(() -> new IllegalStateException(notAccessibleMessage(address)));

        GitHubRepo repo = gitHubClient.findRepo(installationId, address.owner(), address.repo())
                .orElseThrow(() -> new IllegalStateException(notAccessibleMessage(address)));

        Optional<TrackedRepo> existing = trackedRepoRepository.findByGithubRepoId(repo.id());
        if (existing.isPresent() && existing.get().isActive()
                && !existing.get().getTeam().getId().equals(team.getId())) {
            throw alreadyRegistered(repo);
        }
        boolean cleanedUpStale = cleanUpStaleSameName(team, repo);
        return registerChecked(team, repo, installationId, address, existing).withCleanedUpStale(cleanedUpStale);
    }

    private RepoRegistration registerChecked(Team team, GitHubRepo repo, Long installationId, RepoAddress address,
                                             Optional<TrackedRepo> existing) {
        if (existing.isPresent()) {
            TrackedRepo registered = existing.get();
            boolean sameTeam = registered.getTeam().getId().equals(team.getId());
            if (!registered.isActive()) {
                return reactivate(team, registered, repo, installationId, address, sameTeam);
            }
            trackedRepoRepository.updateInstallationId(registered.getId(), installationId);
            registered.changeInstallationId(installationId);
            return RepoRegistration.of(registered, RepoRegistration.Kind.REFRESHED);
        }

        String headSha = findHeadSha(installationId, address, repo);

        try {
            return RepoRegistration.of(trackedRepoRepository.save(new TrackedRepo(
                    team, repo.id(), repo.fullName(), repo.defaultBranch(), headSha, installationId, clock.instant())),
                    RepoRegistration.Kind.NEW);
        } catch (DataIntegrityViolationException e) {
            throw alreadyRegistered(repo);
        }
    }

    /**
     * 같은 팀에 이름은 같지만 GitHub 레포 ID가 다른 활성 등록이 있으면, GitHub에서 삭제 후 같은 이름으로 다시 만든 레포의
     * 옛 등록이다. 수집 때 자동으로 정리하는 것과 같이 비활성화하고 미발송 배치를 버린다.
     *
     * @return 정리한 등록이 있으면 true
     */
    private boolean cleanUpStaleSameName(Team team, GitHubRepo repo) {
        boolean cleaned = false;
        for (TrackedRepo stale : trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(team.getId())) {
            if (!stale.getFullName().equalsIgnoreCase(repo.fullName()) || stale.getGithubRepoId().equals(repo.id())) {
                continue;
            }
            OptionalInt discarded = deactivator.deactivate(stale.getId(), clock.instant());
            if (discarded.isEmpty()) {
                continue;
            }
            cleaned = true;
            StringBuilder detail = new StringBuilder("레포: ").append(stale.getFullName())
                    .append("\n사유: 같은 이름의 새 레포가 등록돼 이전 등록을 정리했어요 (GitHub에서 삭제 후 다시 만들어진 레포)");
            if (discarded.getAsInt() > 0) {
                detail.append("\n아직 보내지 않은 변경 ").append(discarded.getAsInt()).append("건은 보내지 않아요.");
            }
            opsNotifier.notify(new OpsEvent(OpsEvent.Type.REPO_DEACTIVATED, team.getName(), null, detail.toString()));
        }
        return cleaned;
    }

    private RepoRegistration reactivate(Team team, TrackedRepo registered, GitHubRepo repo, Long installationId,
                                        RepoAddress address, boolean sameTeam) {
        String previousTeamName = registered.getTeam().getName();
        String headSha = findHeadSha(installationId, address, repo);

        // "해제돼 있던 동안의 변경은 건너뛴다": 비활성일 때 남은 COLLECTED를 다시 보내지 않는다
        deactivator.discardCollected(registered.getId());
        int updated = trackedRepoRepository.reactivate(registered.getId(), team, installationId,
                repo.fullName(), repo.defaultBranch(), headSha, clock.instant());
        if (updated == 0) {
            // 그사이 다른 등록이 먼저 활성화했다
            throw new IllegalStateException("같은 레포의 다른 등록이 먼저 처리됐어요. /레포목록 으로 확인해 주세요.");
        }
        TrackedRepo reactivated = trackedRepoRepository.findById(registered.getId()).orElseThrow();
        return sameTeam
                ? RepoRegistration.of(reactivated, RepoRegistration.Kind.REACTIVATED)
                : new RepoRegistration(reactivated, RepoRegistration.Kind.MOVED, previousTeamName);
    }

    /**
     * /레포해제: 이 채널 팀의 활성 레포만 비활성화하고, 아직 회차에 담기지 않은 배치(COLLECTED)는 버린다.
     * 이미 회차에 담긴 배치(PREPARED)는 그대로 발송된다.
     * GitHub를 부르지 않는다 (삭제됐거나 접근이 끊긴 레포도 해제할 수 있게).
     * 운영진(서버 관리 권한)만 해제할 수 있다. Discord 서버 설정에서 명령어 권한을 바꿀 수 있어 여기서도 확인한다.
     *
     * @param input        "owner/repo" 또는 GitHub 주소
     * @param actorIsAdmin 실행자에게 서버 관리 권한이 있는지 (Discord 쪽에서 확인해 넘긴다)
     */
    public RepoDeactivation deactivate(Long channelId, String input, long actorId, String actorName, boolean actorIsAdmin) {
        if (!actorIsAdmin) {
            throw new IllegalStateException(ADMIN_ONLY_DEACTIVATE);
        }
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "팀 채팅방에서만 레포를 해제할 수 있어요. 이 채널이 팀으로 등록돼 있는지 운영진에게 확인해 주세요."));
        String fullName = RepoAddress.parseFullNameOrUrl(input).fullName();

        TrackedRepo target = trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(team.getId()).stream()
                .filter(repo -> repo.getFullName().equalsIgnoreCase(fullName))
                .findFirst()
                .orElseThrow(() -> notRegisteredHere(fullName));

        OptionalInt discarded = deactivator.deactivate(target.getId(), clock.instant());
        if (discarded.isEmpty()) {
            throw new IllegalStateException("이미 해제된 레포예요: " + target.getFullName());
        }
        opsNotifier.notify(new OpsEvent(OpsEvent.Type.REPO_DEACTIVATED, team.getName(), null,
                "해제한 사람: " + actorName + " (<@" + actorId + ">)\n레포: " + target.getFullName()));
        return new RepoDeactivation(team.getName(), target.getFullName(), discarded.getAsInt());
    }

    private IllegalStateException notRegisteredHere(String fullName) {
        boolean otherTeam = !trackedRepoRepository.findAllByFullNameIgnoreCaseAndActiveTrue(fullName).isEmpty();
        if (otherTeam) {
            return new IllegalStateException("다른 팀에 등록된 레포라 해제할 수 없어요: " + fullName
                    + "\n레포를 등록한 팀의 채팅방에서 해제해 주세요.");
        }
        return new IllegalStateException("이 팀에 등록된 레포 중에 " + fullName + " 가 없어요. /레포목록 으로 확인해 주세요.");
    }

    /**
     * /레포목록: 이 채널 팀의 활성 레포.
     */
    public RepoList listActive(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        return new RepoList(team.getName(), trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(team.getId()));
    }

    /**
     * /레포전체목록: 모든 팀(등록 순서)과 각 팀의 활성 레포 이름(등록 순서). 활성 레포가 없는 팀도 빈 목록으로 넣는다.
     * 팀 수와 상관없이 팀 조회 한 번, 레포 조회 한 번으로 끝낸다.
     */
    public List<TeamRepos> listAllActiveByTeam() {
        Map<Long, List<String>> reposByTeam = new HashMap<>();
        for (TrackedRepo repo : trackedRepoRepository.findAllByActiveTrueOrderByIdAsc()) {
            // team은 LAZY 프록시지만 id만 읽으므로 추가 조회가 없다
            reposByTeam.computeIfAbsent(repo.getTeam().getId(), id -> new ArrayList<>()).add(repo.getFullName());
        }
        return teamRepository.findAll(Sort.by("id")).stream()
                .map(team -> new TeamRepos(team.getName(), team.getDiscordChannelId(),
                        List.copyOf(reposByTeam.getOrDefault(team.getId(), List.of()))))
                .toList();
    }

    /**
     * /레포해제 자동완성 후보: 이 채널 팀의 활성 레포 fullName 중 입력한 글자를 포함하는 것.
     * 팀 채널이 아니면 후보가 없다.
     */
    public List<String> autocompleteCandidates(Long channelId, String typed) {
        String needle = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        return teamRepository.findByDiscordChannelId(channelId)
                .map(team -> trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(team.getId()).stream()
                        .map(TrackedRepo::getFullName)
                        .filter(name -> name.length() <= MAX_CHOICE_LENGTH)
                        .filter(name -> name.toLowerCase(Locale.ROOT).contains(needle))
                        .limit(MAX_AUTOCOMPLETE_CHOICES)
                        .toList())
                .orElse(List.of());
    }

    private String findHeadSha(Long installationId, RepoAddress address, GitHubRepo repo) {
        return gitHubClient
                .findHeadSha(installationId, address.owner(), address.repo(), repo.defaultBranch())
                .orElse(null);
    }

    private String notAccessibleMessage(RepoAddress address) {
        return "MOA가 `" + address.fullName() + "` 레포에 접근할 수 없어요.\n"
                + "레포 주인(" + address.owner() + ")이 아래 순서대로 진행해 주세요.\n"
                + "1. 이 링크에서 MOA App을 설치해요: " + installUrl + "\n"
                + "2. 설치 화면의 Repository access에서 이 레포를 선택해요.\n"
                + "   (이미 설치했다면 GitHub Settings → Applications → MOA의 Configure에서 이 레포를 추가해요.)\n"
                + "3. 이 채널에서 /레포등록 을 다시 실행해요.\n"
                + "주소에 오타가 없는지도 함께 확인해 주세요.";
    }

    private IllegalStateException alreadyRegistered(GitHubRepo repo) {
        return new IllegalStateException("이미 등록된 레포예요: " + repo.fullName());
    }

    public record RepoList(String teamName, List<TrackedRepo> repos) {
    }

    public record TeamRepos(String teamName, long channelId, List<String> repoFullNames) {
    }
}
