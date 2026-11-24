package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TrackedRepoServiceTest {

    private static final Long CHANNEL_ID = 100L;
    private static final Long INSTALLATION_ID = 555L;
    private static final String INSTALL_URL = "https://github.com/apps/moa-test/installations/new";
    private static final String URL = "https://github.com/owner/repo";
    private static final GitHubRepo GITHUB_REPO = new GitHubRepo(42L, "owner/repo", "main");
    private static final String SHA = "a".repeat(40);
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");

    @Mock
    private TrackedRepoRepository trackedRepoRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private OpsNotifier opsNotifier;
    @Mock
    private TrackedRepoDeactivator deactivator;

    private TrackedRepoService service;
    private Team team;

    @BeforeEach
    void setUp() {
        service = new TrackedRepoService(trackedRepoRepository, teamRepository, gitHubClient, opsNotifier, deactivator,
                Clock.fixed(NOW, ZoneOffset.UTC), INSTALL_URL);
        team = new Team("모아팀", CHANNEL_ID, 1L);
        ReflectionTestUtils.setField(team, "id", 10L);
    }

    @Test
    void 정상_등록하면_설치ID와_기본브랜치_최신SHA를_저장한다() {
        givenTeamChannel();
        givenInstalledRepo();
        given(trackedRepoRepository.findByGithubRepoId(42L)).willReturn(Optional.empty());
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "repo", "main")).willReturn(Optional.of(SHA));
        given(trackedRepoRepository.save(any(TrackedRepo.class))).willAnswer(inv -> inv.getArgument(0));

        RepoRegistration registration = service.register(CHANNEL_ID, URL);
        TrackedRepo result = registration.repo();
        assertThat(registration.kind()).isEqualTo(RepoRegistration.Kind.NEW);

        ArgumentCaptor<TrackedRepo> captor = ArgumentCaptor.forClass(TrackedRepo.class);
        verify(trackedRepoRepository).save(captor.capture());
        TrackedRepo saved = captor.getValue();
        assertThat(saved.getTeam()).isSameAs(team);
        assertThat(saved.getGithubRepoId()).isEqualTo(42L);
        assertThat(saved.getFullName()).isEqualTo("owner/repo");
        assertThat(saved.getDefaultBranch()).isEqualTo("main");
        assertThat(saved.getLastProcessedSha()).isEqualTo(SHA);
        assertThat(saved.getInstallationId()).isEqualTo(INSTALLATION_ID);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getRegisteredAt()).isEqualTo(NOW);
        assertThat(result).isSameAs(saved);
    }

    @Test
    void 팀_채널이_아니면_GitHub를_호출하지_않고_거절한다() {
        given(teamRepository.findByDiscordChannelId(CHANNEL_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CHANNEL_ID, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("팀 채팅방");

        verifyNoInteractions(gitHubClient, trackedRepoRepository);
    }

    @Test
    void 주소_형식이_틀리면_GitHub를_호출하지_않고_거절한다() {
        givenTeamChannel();

        assertThatThrownBy(() -> service.register(CHANNEL_ID, "https://gitlab.com/owner/repo"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(gitHubClient, trackedRepoRepository);
    }

    @Test
    void App이_설치되지_않았으면_설치_링크와_함께_거절한다() {
        givenTeamChannel();
        given(gitHubClient.findInstallationId("owner", "repo")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CHANNEL_ID, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INSTALL_URL)
                .hasMessageContaining("owner/repo");

        verify(trackedRepoRepository, never()).save(any());
    }

    @Test
    void 설치는_됐지만_레포_접근_권한이_없으면_설치_링크와_함께_거절한다() {
        givenTeamChannel();
        given(gitHubClient.findInstallationId("owner", "repo")).willReturn(Optional.of(INSTALLATION_ID));
        given(gitHubClient.findRepo(INSTALLATION_ID, "owner", "repo")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CHANNEL_ID, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INSTALL_URL);

        verify(trackedRepoRepository, never()).save(any());
    }

    @Test
    void 다른_팀이_등록한_레포는_거절한다() {
        givenTeamChannel();
        givenInstalledRepo();
        Team otherTeam = new Team("다른팀", 200L, 2L);
        ReflectionTestUtils.setField(otherTeam, "id", 20L);
        given(trackedRepoRepository.findByGithubRepoId(42L))
                .willReturn(Optional.of(new TrackedRepo(otherTeam, 42L, "owner/repo", "main", SHA, 111L)));

        assertThatThrownBy(() -> service.register(CHANNEL_ID, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 등록된 레포");

        verify(trackedRepoRepository, never()).save(any());
        verify(trackedRepoRepository, never()).updateInstallationId(any(), any());
        verify(trackedRepoRepository, never()).reactivate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 같은_팀이_다시_등록하면_설치_ID만_갱신하고_수집_위치는_유지한다() {
        givenTeamChannel();
        givenInstalledRepo();
        String oldCursor = "c".repeat(40);
        TrackedRepo registered = new TrackedRepo(team, 42L, "owner/repo", "main", oldCursor, 111L);
        ReflectionTestUtils.setField(registered, "id", 5L);
        given(trackedRepoRepository.findByGithubRepoId(42L)).willReturn(Optional.of(registered));

        RepoRegistration registration = service.register(CHANNEL_ID, URL);

        assertThat(registration.kind()).isEqualTo(RepoRegistration.Kind.REFRESHED);
        assertThat(registration.repo()).isSameAs(registered);
        assertThat(registered.getInstallationId()).isEqualTo(INSTALLATION_ID);
        assertThat(registered.getLastProcessedSha()).isEqualTo(oldCursor);
        verify(trackedRepoRepository).updateInstallationId(5L, INSTALLATION_ID);
        verify(trackedRepoRepository, never()).save(any());
        verify(gitHubClient, never()).findHeadSha(any(), any(), any(), any());
    }

    @Test
    void 동시_등록으로_unique_제약에_걸리면_중복_등록으로_안내한다() {
        givenTeamChannel();
        givenInstalledRepo();
        given(trackedRepoRepository.findByGithubRepoId(42L)).willReturn(Optional.empty());
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "repo", "main")).willReturn(Optional.of(SHA));
        given(trackedRepoRepository.save(any(TrackedRepo.class)))
                .willThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.register(CHANNEL_ID, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 등록된 레포");
    }

    @Test
    void 커밋이_없는_빈_레포는_SHA_null로_등록에_성공한다() {
        givenTeamChannel();
        givenInstalledRepo();
        given(trackedRepoRepository.findByGithubRepoId(42L)).willReturn(Optional.empty());
        given(gitHubClient.findHeadSha(INSTALLATION_ID, "owner", "repo", "main")).willReturn(Optional.empty());
        given(trackedRepoRepository.save(any(TrackedRepo.class))).willAnswer(inv -> inv.getArgument(0));

        TrackedRepo result = service.register(CHANNEL_ID, URL).repo();

        assertThat(result.getLastProcessedSha()).isNull();
        assertThat(result.getGithubRepoId()).isEqualTo(42L);
    }

    private void givenTeamChannel() {
        given(teamRepository.findByDiscordChannelId(CHANNEL_ID)).willReturn(Optional.of(team));
    }

    private void givenInstalledRepo() {
        given(gitHubClient.findInstallationId("owner", "repo")).willReturn(Optional.of(INSTALLATION_ID));
        given(gitHubClient.findRepo(INSTALLATION_ID, "owner", "repo")).willReturn(Optional.of(GITHUB_REPO));
    }
}
