package discord.moa.discordmoa.domain.guide.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.domain.digest.service.CycleCalendar;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.global.config.ScheduleProperties;
import discord.moa.discordmoa.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UsageGuideServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long CHANNEL_ID = 100L;
    private static final long PM_ID = 7L;
    private static final long DEV_ID = 8L;

    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TrackedRepoRepository trackedRepoRepository = mock(TrackedRepoRepository.class);
    private final AccountLinkRepository accountLinkRepository = mock(AccountLinkRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T23:59:00Z"), SEOUL);   // 08:59 KST
    private final CycleCalendar calendar = new CycleCalendar(new ScheduleProperties(SEOUL, "0 30 8,20 * * *",
            "0 0 9,21 * * *", Duration.ofMinutes(2), 3, Duration.ofSeconds(30), Duration.ofMinutes(30)));

    @BeforeEach
    void setUp() {
        Team team = new Team("1팀", CHANNEL_ID, PM_ID);
        ReflectionTestUtils.setField(team, "id", 10L);
        given(teamRepository.findByDiscordChannelId(CHANNEL_ID)).willReturn(Optional.of(team));
        given(teamRepository.findByDiscordChannelId(999L)).willReturn(Optional.empty());
        given(trackedRepoRepository.countByTeamIdAndActiveTrue(10L)).willReturn(2L);
        given(accountLinkRepository.findByDiscordUserId(anyLong())).willReturn(Optional.empty());
        given(accountLinkRepository.findByDiscordUserId(DEV_ID)).willReturn(Optional.of(new AccountLink(DEV_ID, 11L, "alice")));
    }

    @Test
    void 팀_채널에서_PM이면_PM으로_보고_다음_발송은_cron_기준이다() {
        UsageGuide guide = service("https://guide").build(CHANNEL_ID, PM_ID);

        assertThat(guide.teamName()).isEqualTo("1팀");
        assertThat(guide.pm()).isTrue();
        assertThat(guide.activeRepoCount()).isEqualTo(2);
        assertThat(guide.nextSend()).isEqualTo(Instant.parse("2026-11-25T00:00:00Z"));   // 오늘 09:00
        assertThat(guide.guideUrl()).isEqualTo("https://guide");

        clock.set(Instant.parse("2026-11-25T12:30:00Z"));   // 21:30 KST
        assertThat(service("https://guide").build(CHANNEL_ID, PM_ID).nextSend())
                .isEqualTo(Instant.parse("2026-11-26T00:00:00Z"));   // 내일 09:00
    }

    @Test
    void 개발자는_연동한_GitHub_아이디를_함께_준다() {
        UsageGuide guide = service("").build(CHANNEL_ID, DEV_ID);

        assertThat(guide.pm()).isFalse();
        assertThat(guide.githubLogin()).isEqualTo("alice");
        assertThat(guide.guideUrl()).isNull();   // 비어 있으면 링크 줄을 뺀다
    }

    @Test
    void 팀_채널이_아니면_레포_수와_다음_발송을_계산하지_않는다() {
        UsageGuide guide = service(null).build(999L, PM_ID);

        assertThat(guide.teamChannel()).isFalse();
        assertThat(guide.pm()).isFalse();
        assertThat(guide.nextSend()).isNull();
        verify(trackedRepoRepository, never()).countByTeamIdAndActiveTrue(anyLong());
    }

    private UsageGuideService service(String guideUrl) {
        return new UsageGuideService(teamRepository, trackedRepoRepository, accountLinkRepository, calendar, clock,
                true, true, guideUrl);
    }
}
