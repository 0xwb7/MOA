package discord.moa.discordmoa.domain.guide.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.domain.digest.service.CycleCalendar;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * /사용법: 실행한 사람과 채널에 맞는 안내에 필요한 값을 모은다. DB만 읽는다.
 */
@Service
public class UsageGuideService {

    private final TeamRepository teamRepository;
    private final TrackedRepoRepository trackedRepoRepository;
    private final AccountLinkRepository accountLinkRepository;
    private final CycleCalendar calendar;
    private final Clock clock;
    private final boolean confirmButtonEnabled;
    private final boolean askButtonEnabled;
    private final String guideUrl;

    public UsageGuideService(TeamRepository teamRepository, TrackedRepoRepository trackedRepoRepository,
                             AccountLinkRepository accountLinkRepository, CycleCalendar calendar, Clock clock,
                             @Value("${discord.confirm-button-enabled:false}") boolean confirmButtonEnabled,
                             @Value("${discord.ask-button-enabled:false}") boolean askButtonEnabled,
                             @Value("${discord.guide-url:}") String guideUrl) {
        this.teamRepository = teamRepository;
        this.trackedRepoRepository = trackedRepoRepository;
        this.accountLinkRepository = accountLinkRepository;
        this.calendar = calendar;
        this.clock = clock;
        this.confirmButtonEnabled = confirmButtonEnabled;
        this.askButtonEnabled = askButtonEnabled;
        this.guideUrl = guideUrl == null || guideUrl.isBlank() ? null : guideUrl.strip();
    }

    @Transactional(readOnly = true)
    public UsageGuide build(long channelId, long userId) {
        Instant now = clock.instant();
        String githubLogin = accountLinkRepository.findByDiscordUserId(userId).map(AccountLink::getGithubLogin).orElse(null);
        Optional<Team> team = teamRepository.findByDiscordChannelId(channelId);
        if (team.isEmpty()) {
            return new UsageGuide(null, false, githubLogin, 0, null, now, calendar.zone(),
                    confirmButtonEnabled, askButtonEnabled, guideUrl);
        }
        Team found = team.get();
        return new UsageGuide(found.getName(), found.getPmDiscordUserId().equals(userId), githubLogin,
                trackedRepoRepository.countByTeamIdAndActiveTrue(found.getId()), calendar.nextSendAfter(now), now,
                calendar.zone(), confirmButtonEnabled, askButtonEnabled, guideUrl);
    }
}
