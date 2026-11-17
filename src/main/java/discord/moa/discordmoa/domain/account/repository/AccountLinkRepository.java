package discord.moa.discordmoa.domain.account.repository;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountLinkRepository extends JpaRepository<AccountLink, Long> {

    Optional<AccountLink> findByDiscordUserId(Long discordUserId);
    Optional<AccountLink> findByGithubUserId(Long githubUserId);
}
