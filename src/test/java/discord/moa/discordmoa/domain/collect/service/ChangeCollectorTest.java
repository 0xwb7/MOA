package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.domain.team.service.TeamBusyException;
import discord.moa.discordmoa.domain.team.service.TeamLocks;
import discord.moa.discordmoa.support.TeamLockHolder;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.client.GitHubInstallationGoneException;
import discord.moa.discordmoa.github.dto.GitHubCommit;
import discord.moa.discordmoa.github.dto.GitHubCommitPage;
import discord.moa.discordmoa.github.dto.GitHubCompare;
import discord.moa.discordmoa.github.dto.GitHubFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChangeCollectorTest {

    private static final Long INSTALLATION_ID = 555L;
    private static final Long NEW_INSTALLATION_ID = 777L;
    private static final String BASE = "b".repeat(40);
    private static final String HEAD = "h".repeat(40);
    private static final Instant T1 = Instant.parse("2026-11-17T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-17T02:00:00Z");

    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private TrackedRepoRepository trackedRepoRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private ChangeBatchRepository changeBatchRepository;

    private final TeamLocks teamLocks = new TeamLocks();
    private ChangeCollector collector;
    private Team team;

    @BeforeEach
    void setUp() {
        ChangeBatchWriter writer = new ChangeBatchWriter(changeBatchRepository, trackedRepoRepository);
        collector = new ChangeCollector(gitHubClient, trackedRepoRepository, teamRepository, writer, teamLocks);
        team = new Team("모아팀", 100L, 1L);
        ReflectionTestUtils.setField(team, "id", 10L);
    }

    @Test
    void 정상_수집하면_커밋과_파일을_저장하고_커서를_head로_옮긴다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD)).willReturn(Optional.of(new GitHubCompare(
                "ahead", 2,
                List.of(commit("c1", "첫 커밋", "alice", 11L, T1), commit("c2", "둘째 커밋", "bob", 12L, T2)),
                List.of(file("src/A.java", "modified", "@@ -1 +1 @@"), file("README.md", "added", "+hello"))
        )));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        verify(changeBatchRepository).save(batch);
        verify(trackedRepoRepository).advanceCursor(1L, BASE, HEAD);
        assertThat(batch.getTrackedRepo()).isSameAs(repo);
        assertThat(batch.getBaseSha()).isEqualTo(BASE);
        assertThat(batch.getHeadSha()).isEqualTo(HEAD);
        assertThat(batch.isForcePushDetected()).isFalse();
        assertThat(batch.isTruncated()).isFalse();
        assertThat(batch.getCommits()).extracting(CommitSnapshot::sha).containsExactly("c1", "c2");
        assertThat(batch.getCommits().get(0)).isEqualTo(new CommitSnapshot(
                "c1", "첫 커밋", "alice", 11L, "alice 이름", T1, "https://github.com/owner/app/commit/c1"));
        assertThat(batch.getFiles()).extracting(FileChange::filename).containsExactly("src/A.java", "README.md");
        assertThat(batch.getFiles()).extracting(FileChange::commitSha).containsOnlyNulls();
    }

    @Test
    void head가_base와_같으면_아무것도_저장하지_않는다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", BASE);

        assertThat(collector.collect(repo)).isEmpty();

        verify(gitHubClient, never()).compare(any(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(changeBatchRepository);
        verify(trackedRepoRepository, never()).advanceCursor(any(), any(), any());
        assertThat(repo.getLastProcessedSha()).isEqualTo(BASE);
    }

    @Test
    void base가_NULL이면_커밋_목록과_커밋별_상세로_수집한다() {
        TrackedRepo repo = repo(1L, "owner/app", null);
        givenHead("owner", "app", HEAD);
        // 목록은 최신순
        given(gitHubClient.listCommits(INSTALLATION_ID, "owner", "app", HEAD, ChangeCollector.INITIAL_COMMIT_LIMIT))
                .willReturn(new GitHubCommitPage(List.of(summary("c2"), summary("c1")), false));
        given(gitHubClient.findCommit(INSTALLATION_ID, "owner", "app", "c1"))
                .willReturn(commitWithFiles("c1", T1, file("A.java", "added", "+a")));
        given(gitHubClient.findCommit(INSTALLATION_ID, "owner", "app", "c2"))
                .willReturn(commitWithFiles("c2", T2, file("A.java", "modified", "+b"), file("B.java", "added", "+c")));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, null, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        verify(gitHubClient, never()).compare(any(), anyString(), anyString(), anyString(), anyString());
        assertThat(batch.getBaseSha()).isNull();
        assertThat(batch.getCommits()).extracting(CommitSnapshot::sha).containsExactly("c1", "c2");
        assertThat(batch.getFiles()).extracting(FileChange::commitSha, FileChange::filename)
                .containsExactly(
                        tuple("c1", "A.java"),
                        tuple("c2", "A.java"),
                        tuple("c2", "B.java"));
        assertThat(batch.isTruncated()).isFalse();
        assertThat(batch.isForcePushDetected()).isFalse();
    }

    @Test
    void base가_NULL이고_커밋이_100개를_넘으면_잘림으로_표시한다() {
        TrackedRepo repo = repo(1L, "owner/app", null);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.listCommits(INSTALLATION_ID, "owner", "app", HEAD, ChangeCollector.INITIAL_COMMIT_LIMIT))
                .willReturn(new GitHubCommitPage(List.of(summary("c1")), true));
        given(gitHubClient.findCommit(INSTALLATION_ID, "owner", "app", "c1")).willReturn(commitWithFiles("c1", T1));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, null, HEAD)).willReturn(1);

        assertThat(collector.collect(repo).orElseThrow().isTruncated()).isTrue();
    }

    @Test
    void base가_NULL이고_여전히_빈_레포면_변경_없음() {
        TrackedRepo repo = repo(1L, "owner/app", null);
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willReturn(Optional.empty());

        assertThat(collector.collect(repo)).isEmpty();

        verify(gitHubClient, never()).listCommits(any(), anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(changeBatchRepository);
        verify(trackedRepoRepository, never()).advanceCursor(any(), any(), any());
    }

    @Test
    void compare가_404면_force_push로_보고_최근_20개만_수집한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD)).willReturn(Optional.empty());
        givenRecentCommits();
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        verify(gitHubClient).listCommits(INSTALLATION_ID, "owner", "app", HEAD, ChangeCollector.FORCE_PUSH_COMMIT_LIMIT);
        assertThat(batch.isForcePushDetected()).isTrue();
        assertThat(batch.isTruncated()).isFalse();
        assertThat(batch.getCommits()).extracting(CommitSnapshot::sha).containsExactly("r1", "r2");
        assertThat(batch.getBaseSha()).isEqualTo(BASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"diverged", "behind"})
    void compare_상태가_diverged나_behind면_force_push로_보고_최근_20개만_수집한다(String status) {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD))
                .willReturn(Optional.of(new GitHubCompare(status, 3, List.of(commit("x", "x", "a", 1L, T1)), List.of())));
        givenRecentCommits();
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        verify(gitHubClient).listCommits(INSTALLATION_ID, "owner", "app", HEAD, ChangeCollector.FORCE_PUSH_COMMIT_LIMIT);
        assertThat(batch.isForcePushDetected()).isTrue();
        assertThat(batch.getCommits()).extracting(CommitSnapshot::sha).containsExactly("r1", "r2");
    }

    @Test
    void 전체_커밋이_받은_250개보다_많으면_잘림으로_표시한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        List<GitHubCommit> commits = IntStream.range(0, 250)
                .mapToObj(i -> commit("c" + i, "m" + i, "alice", 11L, T1))
                .toList();
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD))
                .willReturn(Optional.of(new GitHubCompare("ahead", 320, commits, List.of(file("A.java", "modified", "+a")))));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        assertThat(batch.isTruncated()).isTrue();
        assertThat(batch.getCommits()).hasSize(250);
    }

    @Test
    void 파일이_300개로_꽉_차서_오면_잘림으로_표시한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        List<GitHubFile> files = IntStream.range(0, 300).mapToObj(i -> file("f" + i, "added", "+")).toList();
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD))
                .willReturn(Optional.of(new GitHubCompare("ahead", 1, List.of(commit("c1", "m", "a", 1L, T1)), files)));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        assertThat(collector.collect(repo).orElseThrow().isTruncated()).isTrue();
    }

    @Test
    void author가_null인_커밋은_login과_id를_null로_저장한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        GitHubCommit unlinked = new GitHubCommit("c1", "https://github.com/owner/app/commit/c1",
                new GitHubCommit.Detail("로컬 이메일로 커밋",
                        new GitHubCommit.Signature("홍길동", "local@laptop", T1),
                        new GitHubCommit.Signature("홍길동", "local@laptop", T2)),
                null, null);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD))
                .willReturn(Optional.of(new GitHubCompare("ahead", 1, List.of(unlinked), List.of())));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        CommitSnapshot snapshot = collector.collect(repo).orElseThrow().getCommits().getFirst();

        assertThat(snapshot.authorLogin()).isNull();
        assertThat(snapshot.authorGithubId()).isNull();
        assertThat(snapshot.authorName()).isEqualTo("홍길동");
        assertThat(snapshot.committedAt()).isEqualTo(T2);
    }

    @Test
    void patch는_파일당_10000자로_자르고_표시한다() {
        String longPatch = "x".repeat(ChangeCollector.PATCH_MAX_LENGTH + 1);

        FileChange cut = ChangeCollector.toFileChange(null, file("big.txt", "modified", longPatch));
        FileChange kept = ChangeCollector.toFileChange(null, file("small.txt", "modified", "+a"));
        FileChange binary = ChangeCollector.toFileChange(null, file("logo.png", "added", null));

        assertThat(cut.patch()).hasSize(ChangeCollector.PATCH_MAX_LENGTH);
        assertThat(cut.patchTruncated()).isTrue();
        assertThat(kept.patch()).isEqualTo("+a");
        assertThat(kept.patchTruncated()).isFalse();
        assertThat(binary.patch()).isNull();
        assertThat(binary.patchTruncated()).isFalse();
    }

    @Test
    void 저장이_실패하면_커서를_갱신하지_않는다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD)).willReturn(Optional.of(
                new GitHubCompare("ahead", 1, List.of(commit("c1", "m", "a", 1L, T1)), List.of())));
        given(changeBatchRepository.save(any(ChangeBatch.class)))
                .willThrow(new DataIntegrityViolationException("too large"));

        assertThatThrownBy(() -> collector.collect(repo)).isInstanceOf(DataIntegrityViolationException.class);

        verify(trackedRepoRepository, never()).advanceCursor(any(), any(), any());
        assertThat(repo.getLastProcessedSha()).isEqualTo(BASE);
    }

    @Test
    void 다른_수집이_먼저_커서를_옮겼으면_예외로_롤백시킨다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        givenHead("owner", "app", HEAD);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "app", BASE, HEAD)).willReturn(Optional.of(
                new GitHubCompare("ahead", 1, List.of(commit("c1", "m", "a", 1L, T1)), List.of())));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(0);

        assertThatThrownBy(() -> collector.collect(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner/app");
    }

    @Test
    void collectTeam은_한_레포가_실패해도_다른_레포를_수집한다() {
        TrackedRepo broken = repo(1L, "owner/broken", BASE);
        TrackedRepo healthy = repo(2L, "owner/healthy", BASE);
        TrackedRepo quiet = repo(3L, "owner/quiet", BASE);
        given(trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(10L)).willReturn(List.of(broken, healthy, quiet));
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "broken", "main"))
                .willThrow(new RestClientException("GitHub 502"));
        givenHead("owner", "healthy", HEAD);
        givenHead("owner", "quiet", BASE);
        given(gitHubClient.compare(INSTALLATION_ID, "owner", "healthy", BASE, HEAD)).willReturn(Optional.of(
                new GitHubCompare("ahead", 1, List.of(commit("c1", "m", "a", 1L, T1)), List.of(file("A.java", "added", "+")))));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(2L, BASE, HEAD)).willReturn(1);

        List<RepoCollectResult> results = collector.collectTeam(10L);

        assertThat(results).containsExactly(
                new RepoCollectResult("owner/broken", RepoCollectResult.Outcome.FAILED, 0, 0, false, false,
                        ChangeCollector.UNKNOWN_FAILURE),
                new RepoCollectResult("owner/healthy", RepoCollectResult.Outcome.COLLECTED, 1, 1, false, false, null),
                new RepoCollectResult("owner/quiet", RepoCollectResult.Outcome.NO_CHANGE, 0, 0, false, false, null));
        // 마지막 수집 시각은 GitHub 확인에 성공한 레포만 (변경이 없어도) 갱신한다
        verify(trackedRepoRepository).markCollected(eq(2L), any(Instant.class));
        verify(trackedRepoRepository).markCollected(eq(3L), any(Instant.class));
        verify(trackedRepoRepository, never()).markCollected(eq(1L), any());
    }

    @Test
    void 같은_팀이_처리_중이면_수집테스트를_거절한다() throws Exception {
        given(teamRepository.findByDiscordChannelId(100L)).willReturn(Optional.of(team));

        try (TeamLockHolder ignored = new TeamLockHolder(teamLocks, 10L)) {
            assertThatThrownBy(() -> collector.collectTeamByChannel(100L))
                    .isInstanceOf(TeamBusyException.class)
                    .hasMessageContaining("처리 중");
        }
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void 팀_채널이_아니면_수집하지_않고_거절한다() {
        given(teamRepository.findByDiscordChannelId(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collector.collectTeamByChannel(999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("팀 채팅방");

        verifyNoInteractions(gitHubClient);
    }

    // ---- App 재설치 / 실패 원인 ----

    @Test
    void 설치_토큰이_404면_새_설치_ID로_갱신하고_한_번_다시_수집한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willThrow(gone());
        given(gitHubClient.findInstallationId("owner", "app")).willReturn(Optional.of(NEW_INSTALLATION_ID));
        given(gitHubClient.findHeadSha(NEW_INSTALLATION_ID, "owner", "app", "main")).willReturn(Optional.of(HEAD));
        given(gitHubClient.compare(NEW_INSTALLATION_ID, "owner", "app", BASE, HEAD)).willReturn(Optional.of(new GitHubCompare(
                "ahead", 1, List.of(commit("c1", "로그인 API", "alice", 11L, T1)), List.of(file("A.java", "added", "+a")))));
        givenSaveSucceeds();
        given(trackedRepoRepository.advanceCursor(1L, BASE, HEAD)).willReturn(1);

        ChangeBatch batch = collector.collect(repo).orElseThrow();

        verify(trackedRepoRepository).updateInstallationId(1L, NEW_INSTALLATION_ID);
        assertThat(repo.getInstallationId()).isEqualTo(NEW_INSTALLATION_ID);
        assertThat(batch.getCommits()).extracting(CommitSnapshot::sha).containsExactly("c1");
    }

    @Test
    void 다시_설치되지_않았으면_설치_해제를_원인으로_실패한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(10L)).willReturn(List.of(repo));
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willThrow(gone());
        given(gitHubClient.findInstallationId("owner", "app")).willReturn(Optional.empty());

        List<RepoCollectResult> results = collector.collectTeam(10L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(RepoCollectResult.Outcome.FAILED);
            assertThat(result.failureReason()).isEqualTo(ChangeCollector.INSTALLATION_GONE)
                    .contains("App 설치가 해제됐어요");
        });
        verify(trackedRepoRepository, never()).updateInstallationId(any(), any());
        assertThat(repo.getInstallationId()).isEqualTo(INSTALLATION_ID);
    }

    @Test
    void 재조회한_설치_ID가_예전과_같으면_다시_시도하지_않는다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willThrow(gone());
        given(gitHubClient.findInstallationId("owner", "app")).willReturn(Optional.of(INSTALLATION_ID));

        assertThatThrownBy(() -> collector.collect(repo)).isInstanceOf(GitHubInstallationGoneException.class);
        verify(trackedRepoRepository, never()).updateInstallationId(any(), any());
    }

    @Test
    void 새_설치_ID로도_실패하면_한_번만_시도하고_실패한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willThrow(gone());
        given(gitHubClient.findInstallationId("owner", "app")).willReturn(Optional.of(NEW_INSTALLATION_ID));
        given(gitHubClient.findHeadSha(NEW_INSTALLATION_ID, "owner", "app", "main"))
                .willThrow(new GitHubInstallationGoneException(NEW_INSTALLATION_ID, null));

        assertThatThrownBy(() -> collector.collect(repo)).isInstanceOf(GitHubInstallationGoneException.class);
        verify(gitHubClient).findInstallationId("owner", "app");
    }

    @Test
    void 캐시된_토큰이_401이면_토큰을_버리고_다시_받아_재설치를_확인한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main"))
                .willThrow(httpError(HttpStatus.UNAUTHORIZED))
                .willThrow(gone());
        given(gitHubClient.findInstallationId("owner", "app")).willReturn(Optional.of(NEW_INSTALLATION_ID));
        given(gitHubClient.findHeadSha(NEW_INSTALLATION_ID, "owner", "app", "main")).willReturn(Optional.of(BASE));

        assertThat(collector.collect(repo)).isEmpty();

        verify(gitHubClient).forgetInstallationToken(INSTALLATION_ID);
        verify(trackedRepoRepository).updateInstallationId(1L, NEW_INSTALLATION_ID);
    }

    @Test
    void 레포에_접근할_수_없으면_레포_선택_누락을_원인으로_실패한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(10L)).willReturn(List.of(repo));
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main")).willThrow(httpError(HttpStatus.NOT_FOUND));

        assertThat(collector.collectTeam(10L)).singleElement()
                .extracting(RepoCollectResult::failureReason)
                .isEqualTo(ChangeCollector.REPO_NOT_ACCESSIBLE);
        verify(gitHubClient, never()).findInstallationId(anyString(), anyString());
    }

    @Test
    void GitHub가_응답하지_않으면_시간_초과를_원인으로_실패한다() {
        TrackedRepo repo = repo(1L, "owner/app", BASE);
        given(trackedRepoRepository.findAllByTeamIdAndActiveTrueOrderByIdAsc(10L)).willReturn(List.of(repo));
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "app", "main"))
                .willThrow(new ResourceAccessException("Read timed out"));

        assertThat(collector.collectTeam(10L)).singleElement()
                .extracting(RepoCollectResult::failureReason)
                .isEqualTo(ChangeCollector.GITHUB_UNREACHABLE);
    }

    // ---- fixtures ----

    private static GitHubInstallationGoneException gone() {
        return new GitHubInstallationGoneException(INSTALLATION_ID, null);
    }

    private static HttpClientErrorException httpError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private TrackedRepo repo(Long id, String fullName, String lastProcessedSha) {
        TrackedRepo repo = new TrackedRepo(team, id * 100, fullName, "main", lastProcessedSha, INSTALLATION_ID);
        ReflectionTestUtils.setField(repo, "id", id);
        return repo;
    }

    private void givenHead(String owner, String name, String sha) {
        given(gitHubClient.findHeadSha(INSTALLATION_ID, owner, name, "main")).willReturn(Optional.of(sha));
    }

    private void givenSaveSucceeds() {
        given(changeBatchRepository.save(any(ChangeBatch.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenRecentCommits() {
        given(gitHubClient.listCommits(INSTALLATION_ID, "owner", "app", HEAD, ChangeCollector.FORCE_PUSH_COMMIT_LIMIT))
                .willReturn(new GitHubCommitPage(List.of(summary("r2"), summary("r1")), true));
        given(gitHubClient.findCommit(INSTALLATION_ID, "owner", "app", "r1"))
                .willReturn(commitWithFiles("r1", T1, file("A.java", "modified", "+a")));
        given(gitHubClient.findCommit(INSTALLATION_ID, "owner", "app", "r2"))
                .willReturn(commitWithFiles("r2", T2));
    }

    private static GitHubCommit commit(String sha, String message, String login, Long githubId, Instant date) {
        return new GitHubCommit(sha, "https://github.com/owner/app/commit/" + sha,
                new GitHubCommit.Detail(message,
                        new GitHubCommit.Signature(login + " 이름", login + "@example.com", date),
                        new GitHubCommit.Signature(login + " 이름", login + "@example.com", date)),
                new GitHubCommit.Account(githubId, login),
                null);
    }

    private static GitHubCommit summary(String sha) {
        return commit(sha, "msg " + sha, "alice", 11L, T1);
    }

    private static GitHubCommit commitWithFiles(String sha, Instant date, GitHubFile... files) {
        GitHubCommit base = commit(sha, "msg " + sha, "alice", 11L, date);
        return new GitHubCommit(base.sha(), base.htmlUrl(), base.commit(), base.author(), List.of(files));
    }

    private static GitHubFile file(String filename, String status, String patch) {
        return new GitHubFile(filename, status, 1, 0, patch);
    }
}
