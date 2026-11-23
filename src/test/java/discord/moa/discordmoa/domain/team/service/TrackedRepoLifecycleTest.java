package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.collect.service.ChangeBatchWriter;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import discord.moa.discordmoa.support.H2Constraints;
import discord.moa.discordmoa.support.MutableClock;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * /레포해제·/레포목록·재등록 흐름을 실제 JPA(H2)로 검증한다.
 * 비활성화와 재활성화가 조건부 UPDATE라서 리포지토리를 mock으로 두지 않는다. GitHub와 운영 알림만 mock.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ChangeBatchWriter.class)
class TrackedRepoLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    private static final Long CHANNEL_ID = 100L;
    private static final Long OTHER_CHANNEL_ID = 200L;
    private static final Long INSTALLATION_ID = 555L;
    private static final String OLD_CURSOR = "a".repeat(40);
    private static final String NEW_HEAD = "b".repeat(40);

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TrackedRepoRepository trackedRepoRepository;
    @Autowired
    private ChangeBatchRepository changeBatchRepository;
    @Autowired
    private ChangeBatchWriter changeBatchWriter;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final GitHubClient gitHubClient = mock(GitHubClient.class);
    private final OpsNotifier opsNotifier = mock(OpsNotifier.class);
    private final MutableClock clock = new MutableClock(NOW, ZoneId.of("Asia/Seoul"));

    private TrackedRepoService service;
    private ChangeCollector collector;
    private Team team;
    private Team otherTeam;
    private TrackedRepo backend;
    private TrackedRepo frontend;
    private TrackedRepo otherApp;

    @BeforeEach
    void setUp() {
        H2Constraints.dropChecks(entityManagerFactory);
        team = teamRepository.save(new Team("1팀", CHANNEL_ID, 7L));
        otherTeam = teamRepository.save(new Team("2팀", OTHER_CHANNEL_ID, 8L));
        backend = trackedRepoRepository.save(new TrackedRepo(team, 1L, "team1/backend", "main", OLD_CURSOR, INSTALLATION_ID));
        frontend = trackedRepoRepository.save(new TrackedRepo(team, 2L, "team1/frontend", "main", OLD_CURSOR, INSTALLATION_ID));
        otherApp = trackedRepoRepository.save(new TrackedRepo(otherTeam, 3L, "team2/app", "main", OLD_CURSOR, INSTALLATION_ID));
        service = new TrackedRepoService(trackedRepoRepository, teamRepository, gitHubClient, opsNotifier, clock,
                "https://github.com/apps/moa-test/installations/new");
        collector = new ChangeCollector(gitHubClient, trackedRepoRepository, teamRepository, changeBatchWriter, new TeamLocks());
    }

    @AfterEach
    void tearDown() {
        changeBatchRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
    }

    // ---- 해제 ----

    @Test
    void 해제한_레포는_수집_정기_회차_판단_목록_자동완성에서_빠진다() {
        RepoDeactivation result = service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");

        assertThat(result).isEqualTo(new RepoDeactivation("1팀", "team1/backend"));
        TrackedRepo deactivated = reload(backend);
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getDeactivatedAt()).isEqualTo(NOW);

        assertThat(service.listActive(CHANNEL_ID).repos()).extracting(TrackedRepo::getFullName)
                .containsExactly("team1/frontend");
        assertThat(service.autocompleteCandidates(CHANNEL_ID, "")).containsExactly("team1/frontend");
        // 아직 활성 레포(frontend)가 남아 있으니 정기 회차 대상이다
        assertThat(teamRepository.findAllWithTrackedRepo()).extracting(Team::getName).containsExactly("1팀", "2팀");

        givenHead("team1", "frontend", OLD_CURSOR);   // 변경 없음
        List<RepoCollectResult> collected = collector.collectTeam(team.getId());

        assertThat(collected).extracting(RepoCollectResult::repoFullName).containsExactly("team1/frontend");
        verify(gitHubClient, never()).findHeadSha(any(), eq("team1"), eq("backend"), any());
        assertThat(reload(frontend).getLastCollectedAt()).isNotNull();   // 변경이 없어도 확인 시각은 남는다
        assertThat(reload(backend).getLastCollectedAt()).isNull();
    }

    @Test
    void 마지막_레포를_해제한_팀은_정기_회차_대상에서_빠진다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");
        service.deactivate(CHANNEL_ID, "https://github.com/Team1/Frontend", 7L, "alice");   // 주소, 대소문자 무관

        assertThat(teamRepository.findAllWithTrackedRepo()).extracting(Team::getName).containsExactly("2팀");
        assertThat(teamRepository.findAllForSendCycle(NOW)).extracting(Team::getName).containsExactly("2팀");
        assertThat(trackedRepoRepository.existsByTeamIdAndActiveTrue(team.getId())).isFalse();
        assertThat(service.listActive(CHANNEL_ID).repos()).isEmpty();
        assertThat(collector.collectTeam(team.getId())).isEmpty();
    }

    @Test
    void 다른_팀의_레포는_해제할_수_없다() {
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "team2/app", 7L, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 팀에 등록된 레포라 해제할 수 없어요");

        assertThat(reload(otherApp).isActive()).isTrue();
        verifyNoInteractions(opsNotifier);
    }

    @Test
    void 이_팀에_없거나_이미_해제한_레포는_목록_안내와_함께_거절한다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");

        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이 팀에 등록된 레포 중에 team1/backend 가 없어요");
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "nobody/unknown", 7L, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/레포목록");
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "레포 이름 아님", 7L, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 해제해도_이미_수집된_COLLECTED와_PREPARED_배치는_그대로다() {
        ChangeBatch collected = batch(backend, ChangeBatchStatus.COLLECTED);
        ChangeBatch prepared = batch(backend, ChangeBatchStatus.PREPARED);

        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");

        assertThat(changeBatchRepository.findById(collected.getId()).orElseThrow().getStatus())
                .isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(changeBatchRepository.findById(prepared.getId()).orElseThrow().getStatus())
                .isEqualTo(ChangeBatchStatus.PREPARED);
        // 다음 발송이 이 팀의 COLLECTED 배치를 그대로 가져간다
        assertThat(changeBatchRepository.findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(team.getId(), ChangeBatchStatus.COLLECTED))
                .extracting(ChangeBatch::getId).containsExactly(collected.getId());
    }

    @Test
    void 해제하면_누가_어느_팀의_어느_레포를_해제했는지_운영_알림을_보낸다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 77L, "alice");

        ArgumentCaptor<OpsEvent> captor = ArgumentCaptor.forClass(OpsEvent.class);
        verify(opsNotifier).notify(captor.capture());
        OpsEvent event = captor.getValue();
        assertThat(event.type()).isEqualTo(OpsEvent.Type.REPO_DEACTIVATED);
        assertThat(event.type().severity()).isEqualTo(OpsEvent.Severity.INFO);
        assertThat(event.teamName()).isEqualTo("1팀");
        assertThat(event.detail()).contains("alice", "<@77>", "team1/backend");
    }

    // ---- 다시 등록 ----

    @Test
    void 같은_팀이_해제한_레포를_다시_등록하면_활성화하고_커서를_지금으로_초기화한다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");
        clock.set(NOW.plus(Duration.ofHours(5)));
        givenInstalled("team1", "backend", 1L, 777L);

        RepoRegistration registration = service.register(CHANNEL_ID, "https://github.com/team1/backend");

        assertThat(registration.kind()).isEqualTo(RepoRegistration.Kind.REACTIVATED);
        TrackedRepo repo = reload(backend);
        assertThat(repo.isActive()).isTrue();
        assertThat(repo.getDeactivatedAt()).isNull();
        assertThat(repo.getLastProcessedSha()).isEqualTo(NEW_HEAD);   // 해제돼 있던 동안의 변경은 건너뛴다
        assertThat(repo.getInstallationId()).isEqualTo(777L);
        assertThat(repo.getRegisteredAt()).isEqualTo(NOW.plus(Duration.ofHours(5)));
        assertThat(registration.repo().getLastProcessedSha()).isEqualTo(NEW_HEAD);
        assertThat(trackedRepoRepository.count()).isEqualTo(3);   // 새 행을 만들지 않는다
        assertThat(service.listActive(CHANNEL_ID).repos()).extracting(TrackedRepo::getFullName)
                .containsExactly("team1/backend", "team1/frontend");
    }

    @Test
    void 다른_팀이_해제된_레포를_등록하면_그_팀으로_옮기고_커서를_초기화한다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice");
        givenInstalled("team1", "backend", 1L, INSTALLATION_ID);

        RepoRegistration registration = service.register(OTHER_CHANNEL_ID, "https://github.com/team1/backend");

        assertThat(registration.kind()).isEqualTo(RepoRegistration.Kind.MOVED);
        assertThat(registration.previousTeamName()).isEqualTo("1팀");
        TrackedRepo repo = trackedRepoRepository.findByGithubRepoId(1L).orElseThrow();
        assertThat(repo.getTeam().getId()).isEqualTo(otherTeam.getId());
        assertThat(repo.isActive()).isTrue();
        assertThat(repo.getLastProcessedSha()).isEqualTo(NEW_HEAD);
        assertThat(service.listActive(OTHER_CHANNEL_ID).repos()).extracting(TrackedRepo::getFullName)
                .containsExactly("team1/backend", "team2/app");
        assertThat(service.listActive(CHANNEL_ID).repos()).extracting(TrackedRepo::getFullName)
                .containsExactly("team1/frontend");
    }

    @Test
    void 활성_레포를_다른_팀이_등록하면_거절한다() {
        givenInstalled("team1", "backend", 1L, INSTALLATION_ID);

        assertThatThrownBy(() -> service.register(OTHER_CHANNEL_ID, "https://github.com/team1/backend"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 등록된 레포");

        TrackedRepo repo = trackedRepoRepository.findByGithubRepoId(1L).orElseThrow();
        assertThat(repo.getTeam().getId()).isEqualTo(team.getId());
        assertThat(repo.getLastProcessedSha()).isEqualTo(OLD_CURSOR);
    }

    // ---- 자동완성 ----

    @Test
    void 자동완성_후보는_그_채널_팀의_활성_레포만이다() {
        service.deactivate(CHANNEL_ID, "team1/frontend", 7L, "alice");

        assertThat(service.autocompleteCandidates(CHANNEL_ID, "")).containsExactly("team1/backend");
        assertThat(service.autocompleteCandidates(CHANNEL_ID, "BACK")).containsExactly("team1/backend");
        assertThat(service.autocompleteCandidates(CHANNEL_ID, "front")).isEmpty();
        assertThat(service.autocompleteCandidates(CHANNEL_ID, "team2")).isEmpty();   // 다른 팀 레포는 안 보인다
        assertThat(service.autocompleteCandidates(999L, "")).isEmpty();               // 팀 채널이 아니면 후보 없음
    }

    // ---- DDL ----

    @Test
    void active_컬럼은_DB_기본값이_1이라_값_없이_들어간_기존_행도_활성이다() {
        // ddl-auto update가 기존 테이블에 컬럼을 추가하면 기존 행은 DB 기본값으로 채워진다. 그 상황을 값 없는 insert로 재현한다
        jdbcTemplate.update("insert into tracked_repo (team_id, github_repo_id, full_name, default_branch, installation_id) "
                + "values (?, 99, 'legacy/app', 'main', 555)", team.getId());

        TrackedRepo legacy = trackedRepoRepository.findByGithubRepoId(99L).orElseThrow();
        assertThat(legacy.isActive()).isTrue();
        assertThat(legacy.getRegisteredAt()).isNull();
        assertThat(service.listActive(CHANNEL_ID).repos()).extracting(TrackedRepo::getFullName).contains("legacy/app");
    }

    // ---- helpers ----

    private TrackedRepo reload(TrackedRepo repo) {
        return trackedRepoRepository.findById(repo.getId()).orElseThrow();
    }

    private void givenHead(String owner, String name, String sha) {
        given(gitHubClient.findHeadSha(INSTALLATION_ID, owner, name, "main")).willReturn(Optional.of(sha));
    }

    private void givenInstalled(String owner, String name, Long githubRepoId, Long installationId) {
        given(gitHubClient.findInstallationId(owner, name)).willReturn(Optional.of(installationId));
        given(gitHubClient.findRepo(installationId, owner, name))
                .willReturn(Optional.of(new GitHubRepo(githubRepoId, owner + "/" + name, "main")));
        given(gitHubClient.findHeadSha(installationId, owner, name, "main")).willReturn(Optional.of(NEW_HEAD));
    }

    private ChangeBatch batch(TrackedRepo repo, ChangeBatchStatus status) {
        String sha = status.name().toLowerCase() + "-head";
        CommitSnapshot commit = new CommitSnapshot(sha, "커밋", "alice", 11L, "Alice", NOW,
                "https://github.com/" + repo.getFullName() + "/commit/" + sha);
        ChangeBatch batch = new ChangeBatch(repo, "base", sha, NOW, false, false, List.of(commit), List.of());
        ReflectionTestUtils.setField(batch, "status", status);
        return changeBatchRepository.save(batch);
    }
}
