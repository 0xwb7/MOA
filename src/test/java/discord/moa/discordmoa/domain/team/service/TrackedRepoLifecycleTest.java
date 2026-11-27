package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.collect.service.ChangeBatchWriter;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.ControllerClassPaths;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
@Import({ChangeBatchWriter.class, TrackedRepoDeactivator.class})
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
    private TrackedRepoDeactivator deactivator;
    @Autowired
    private DigestRepository digestRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
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
        service = new TrackedRepoService(trackedRepoRepository, teamRepository, gitHubClient, opsNotifier, deactivator, clock,
                "https://github.com/apps/moa-test/installations/new");
        collector = new ChangeCollector(gitHubClient, trackedRepoRepository, teamRepository, changeBatchWriter, new TeamLocks(),
                deactivator, opsNotifier, new ControllerClassPaths(gitHubClient));
    }

    @AfterEach
    void tearDown() {
        changeBatchRepository.deleteAllInBatch();
        digestRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
    }

    // ---- 전체 목록 ----

    @Test
    void 전체_목록은_팀과_활성_레포를_등록_순서로_모으고_레포_없는_팀도_넣는다() {
        trackedRepoRepository.save(new TrackedRepo(team, 4L, "team1/docs", "main", OLD_CURSOR, INSTALLATION_ID));
        teamRepository.save(new Team("3팀", 300L, 9L));
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);

        assertThat(service.listAllActiveByTeam()).containsExactly(
                new TrackedRepoService.TeamRepos("1팀", CHANNEL_ID, List.of("team1/frontend", "team1/docs")),
                new TrackedRepoService.TeamRepos("2팀", OTHER_CHANNEL_ID, List.of("team2/app")),
                new TrackedRepoService.TeamRepos("3팀", 300L, List.of()));
    }

    // ---- 해제 ----

    @Test
    void 해제한_레포는_수집_정기_회차_판단_목록_자동완성에서_빠진다() {
        RepoDeactivation result = service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);

        assertThat(result).isEqualTo(new RepoDeactivation("1팀", "team1/backend", 0));
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
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);
        service.deactivate(CHANNEL_ID, "https://github.com/Team1/Frontend", 7L, "alice", true);   // 주소, 대소문자 무관

        assertThat(teamRepository.findAllWithTrackedRepo()).extracting(Team::getName).containsExactly("2팀");
        assertThat(teamRepository.findAllForSendCycle(NOW)).extracting(Team::getName).containsExactly("2팀");
        assertThat(trackedRepoRepository.existsByTeamIdAndActiveTrue(team.getId())).isFalse();
        assertThat(service.listActive(CHANNEL_ID).repos()).isEmpty();
        assertThat(collector.collectTeam(team.getId())).isEmpty();
    }

    @Test
    void 운영진이_아니면_해제를_거절하고_레포와_배치는_그대로_둔다() {
        ChangeBatch pending = changeBatchRepository.save(new ChangeBatch(backend, OLD_CURSOR, "b".repeat(40), NOW,
                false, false, List.of(), List.of()));

        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("레포 해제는 운영진에게 요청해 주세요.");

        assertThat(reload(backend).isActive()).isTrue();
        assertThat(changeBatchRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(ChangeBatchStatus.COLLECTED);
        verifyNoInteractions(opsNotifier);
    }

    @Test
    void 다른_팀의_레포는_해제할_수_없다() {
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "team2/app", 7L, "alice", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 팀에 등록된 레포라 해제할 수 없어요");

        assertThat(reload(otherApp).isActive()).isTrue();
        verifyNoInteractions(opsNotifier);
    }

    @Test
    void 이_팀에_없거나_이미_해제한_레포는_목록_안내와_함께_거절한다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);

        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이 팀에 등록된 레포 중에 team1/backend 가 없어요");
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "nobody/unknown", 7L, "alice", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/레포목록");
        assertThatThrownBy(() -> service.deactivate(CHANNEL_ID, "레포 이름 아님", 7L, "alice", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 해제하면_아직_회차에_담기지_않은_COLLECTED는_버리고_PREPARED는_그대로_둔다() {
        ChangeBatch collected = batch(backend, ChangeBatchStatus.COLLECTED, 3);
        ChangeBatch collected2 = batch(backend, ChangeBatchStatus.COLLECTED, 2);
        ChangeBatch prepared = batch(backend, ChangeBatchStatus.PREPARED, 1);
        ChangeBatch sent = batch(backend, ChangeBatchStatus.SENT, 1);
        ChangeBatch otherRepo = batch(frontend, ChangeBatchStatus.COLLECTED, 1);

        RepoDeactivation result = service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);

        assertThat(result.discardedCommitCount()).isEqualTo(5);   // 버린 배치의 커밋 수
        assertThat(status(collected)).isEqualTo(ChangeBatchStatus.DISCARDED);
        assertThat(status(collected2)).isEqualTo(ChangeBatchStatus.DISCARDED);
        assertThat(status(prepared)).isEqualTo(ChangeBatchStatus.PREPARED);   // 이미 회차에 담긴 것은 그대로 발송
        assertThat(status(sent)).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(status(otherRepo)).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(changeBatchRepository.findAllOfActiveReposByTeamIdAndStatus(team.getId(), ChangeBatchStatus.COLLECTED))
                .extracting(ChangeBatch::getId).containsExactly(otherRepo.getId());
    }

    @Test
    void 보낼_변경이_없던_레포를_해제하면_버린_변경은_0건이다() {
        assertThat(service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true).discardedCommitCount()).isZero();
    }

    @Test
    void 해제한_레포에_COLLECTED가_남아_있어도_회차에_담지_않는다() {
        ChangeBatch leftover = batch(backend, ChangeBatchStatus.COLLECTED, 1);
        ChangeBatch active = batch(frontend, ChangeBatchStatus.COLLECTED, 1);
        // 해제 플래그만 바뀐 상태 (예: 해제 직후 발송 실패로 PREPARED가 COLLECTED로 되돌아옴)
        trackedRepoRepository.deactivate(backend.getId(), NOW);
        Digest digest = digestRepository.save(new Digest(team, NOW, CycleKind.SCHEDULED, NOW));

        int fallback = inTransaction(() -> changeBatchRepository.claimAllOfTeam(team.getId(), digest,
                ChangeBatchStatus.COLLECTED, ChangeBatchStatus.PREPARED));
        assertThat(fallback).isEqualTo(1);
        assertThat(status(leftover)).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(status(active)).isEqualTo(ChangeBatchStatus.PREPARED);

        int llm = inTransaction(() -> changeBatchRepository.claimByIds(List.of(leftover.getId()), digest,
                ChangeBatchStatus.COLLECTED, ChangeBatchStatus.PREPARED));
        assertThat(llm).isZero();
        assertThat(status(leftover)).isEqualTo(ChangeBatchStatus.COLLECTED);
        // 요약이 읽는 목록에도 없다
        assertThat(changeBatchRepository.findAllOfActiveReposByTeamIdAndStatus(team.getId(), ChangeBatchStatus.COLLECTED))
                .isEmpty();
    }

    @Test
    void 해제하면_누가_어느_팀의_어느_레포를_해제했는지_운영_알림을_보낸다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 77L, "alice", true);

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
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);
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
    void 다시_등록하면_해제돼_있던_동안_남은_COLLECTED를_보내지_않는다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);
        ChangeBatch revertedAfterDeactivation = batch(backend, ChangeBatchStatus.COLLECTED, 1);
        givenInstalled("team1", "backend", 1L, INSTALLATION_ID);

        service.register(CHANNEL_ID, "https://github.com/team1/backend");

        assertThat(status(revertedAfterDeactivation)).isEqualTo(ChangeBatchStatus.DISCARDED);
    }

    @Test
    void 다른_팀이_해제된_레포를_등록하면_그_팀으로_옮기고_커서를_초기화한다() {
        service.deactivate(CHANNEL_ID, "team1/backend", 7L, "alice", true);
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
    void 삭제_후_같은_이름으로_다시_만든_레포를_등록하면_이전_등록을_정리한다() {
        ChangeBatch leftover = batch(backend, ChangeBatchStatus.COLLECTED, 2);
        ChangeBatch prepared = batch(backend, ChangeBatchStatus.PREPARED, 1);
        givenInstalled("team1", "backend", 50L, INSTALLATION_ID);   // 새 레포는 GitHub ID가 다르다

        RepoRegistration registration = service.register(CHANNEL_ID, "https://github.com/team1/backend");

        assertThat(registration.kind()).isEqualTo(RepoRegistration.Kind.NEW);
        assertThat(registration.cleanedUpStale()).isTrue();
        assertThat(registration.repo().getGithubRepoId()).isEqualTo(50L);
        TrackedRepo old = reload(backend);
        assertThat(old.isActive()).isFalse();
        assertThat(old.getDeactivatedAt()).isEqualTo(NOW);
        assertThat(status(leftover)).isEqualTo(ChangeBatchStatus.DISCARDED);
        assertThat(status(prepared)).isEqualTo(ChangeBatchStatus.PREPARED);
        assertThat(service.listActive(CHANNEL_ID).repos()).extracting(TrackedRepo::getGithubRepoId)
                .containsExactlyInAnyOrder(2L, 50L);

        ArgumentCaptor<OpsEvent> captor = ArgumentCaptor.forClass(OpsEvent.class);
        verify(opsNotifier).notify(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(OpsEvent.Type.REPO_DEACTIVATED);
        assertThat(captor.getValue().teamName()).isEqualTo("1팀");
        assertThat(captor.getValue().detail()).contains("team1/backend", "이전 등록을 정리했어요", "변경 2건은 보내지 않아요");
    }

    @Test
    void 같은_레포를_다시_등록하거나_다른_팀의_같은_이름_레포는_정리하지_않는다() {
        givenInstalled("team1", "backend", 1L, INSTALLATION_ID);
        RepoRegistration refreshed = service.register(CHANNEL_ID, "https://github.com/team1/backend");
        assertThat(refreshed.kind()).isEqualTo(RepoRegistration.Kind.REFRESHED);
        assertThat(refreshed.cleanedUpStale()).isFalse();

        givenInstalled("team2", "app", 60L, INSTALLATION_ID);   // 2팀의 team2/app과 이름만 같은 새 레포
        RepoRegistration other = service.register(CHANNEL_ID, "https://github.com/team2/app");
        assertThat(other.cleanedUpStale()).isFalse();
        assertThat(reload(otherApp).isActive()).isTrue();   // 다른 팀 것은 그 팀의 다음 수집 때 정리된다
        verify(opsNotifier, never()).notify(any());
    }

    @Test
    void 수집할_때_레포_ID가_다르면_그_레코드를_해제하고_미발송_배치를_버린다() {
        ChangeBatch leftover = batch(frontend, ChangeBatchStatus.COLLECTED, 1);
        givenHead("team1", "backend", OLD_CURSOR);
        given(gitHubClient.findRepo(INSTALLATION_ID, "team1", "frontend"))
                .willReturn(Optional.of(new GitHubRepo(70L, "team1/frontend", "main")));

        List<RepoCollectResult> results = collector.collectTeam(team.getId());

        assertThat(results).extracting(RepoCollectResult::repoFullName, RepoCollectResult::outcome).containsExactly(
                tuple("team1/backend", RepoCollectResult.Outcome.NO_CHANGE),
                tuple("team1/frontend", RepoCollectResult.Outcome.DEACTIVATED));
        assertThat(reload(frontend).isActive()).isFalse();
        assertThat(status(leftover)).isEqualTo(ChangeBatchStatus.DISCARDED);
        verify(gitHubClient, never()).findHeadSha(any(), eq("team1"), eq("frontend"), any());
        verify(opsNotifier).notify(any(OpsEvent.class));
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
        service.deactivate(CHANNEL_ID, "team1/frontend", 7L, "alice", true);

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

    @Test
    void classPath가_없던_예전_배치의_파일_JSON도_그대로_읽는다() {
        jdbcTemplate.update("""
                insert into change_batch (tracked_repo_id, base_sha, head_sha, collected_at, status,
                                          force_push_detected, truncated, commits, files)
                values (?, 'base', 'head', ?, 'COLLECTED', false, false, ? FORMAT JSON, ? FORMAT JSON)
                """, backend.getId(), java.sql.Timestamp.from(NOW), "[]",
                "[{\"commitSha\":null,\"filename\":\"src/A.java\",\"status\":\"modified\",\"additions\":1,"
                        + "\"deletions\":0,\"patch\":\"+a\",\"patchTruncated\":false}]");

        ChangeBatch legacy = changeBatchRepository.findAll().getFirst();

        assertThat(legacy.getFiles()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("src/A.java");
            assertThat(file.classPath()).isNull();
        });
    }

    // ---- helpers ----

    private TrackedRepo reload(TrackedRepo repo) {
        return trackedRepoRepository.findById(repo.getId()).orElseThrow();
    }

    private void givenHead(String owner, String name, String sha) {
        String fullName = owner + "/" + name;
        Long githubRepoId = java.util.stream.Stream.of(backend, frontend, otherApp)
                .filter(repo -> repo.getFullName().equals(fullName)).findFirst().orElseThrow().getGithubRepoId();
        given(gitHubClient.findRepo(INSTALLATION_ID, owner, name))
                .willReturn(Optional.of(new GitHubRepo(githubRepoId, fullName, "main")));
        given(gitHubClient.findHeadSha(INSTALLATION_ID, owner, name, "main")).willReturn(Optional.of(sha));
    }

    private void givenInstalled(String owner, String name, Long githubRepoId, Long installationId) {
        given(gitHubClient.findInstallationId(owner, name)).willReturn(Optional.of(installationId));
        given(gitHubClient.findRepo(installationId, owner, name))
                .willReturn(Optional.of(new GitHubRepo(githubRepoId, owner + "/" + name, "main")));
        given(gitHubClient.findHeadSha(installationId, owner, name, "main")).willReturn(Optional.of(NEW_HEAD));
    }

    private ChangeBatch batch(TrackedRepo repo, ChangeBatchStatus status) {
        return batch(repo, status, 1);
    }

    private ChangeBatch batch(TrackedRepo repo, ChangeBatchStatus status, int commitCount) {
        String sha = status.name().toLowerCase() + "-head";
        List<CommitSnapshot> commits = IntStream.range(0, commitCount)
                .mapToObj(i -> new CommitSnapshot(sha + i, "커밋", "alice", 11L, "Alice", NOW,
                        "https://github.com/" + repo.getFullName() + "/commit/" + sha + i))
                .toList();
        ChangeBatch batch = new ChangeBatch(repo, "base", sha, NOW, false, false, commits, List.of());
        ReflectionTestUtils.setField(batch, "status", status);
        return changeBatchRepository.save(batch);
    }

    private ChangeBatchStatus status(ChangeBatch batch) {
        return changeBatchRepository.findById(batch.getId()).orElseThrow().getStatus();
    }

    private int inTransaction(java.util.function.IntSupplier work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.getAsInt());
    }
}
