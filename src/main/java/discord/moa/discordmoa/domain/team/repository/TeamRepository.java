package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByName(String name);
    Optional<Team> findByDiscordChannelId(Long discordChannelId);
}
