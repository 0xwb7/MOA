package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;

    @Transactional
    public TeamRegistration register(String name, Long channelId, Long pmUserId) {
        teamRepository.findByDiscordChannelId(channelId)
                .filter(team -> !team.getName().equals(name))
                .ifPresent(team -> {
                    throw new IllegalStateException(
                            "이 채널은 이미 '" + team.getName() + "'이 사용 중이에요.");
                });

        return teamRepository.findByName(name)
                .map(team -> {
                    Long previousPm = team.getPmDiscordUserId();
                    Long previousChannel = team.getDiscordChannelId();
                    team.update(channelId, pmUserId);
                    return TeamRegistration.updated(name, pmUserId, previousPm, channelId, previousChannel);
                })
                .orElseGet(() -> {
                    teamRepository.save(new Team(name, channelId, pmUserId));
                    return TeamRegistration.created(name, pmUserId, channelId);
                });
    }
}
