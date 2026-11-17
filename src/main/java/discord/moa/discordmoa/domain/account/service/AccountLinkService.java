package discord.moa.discordmoa.domain.account.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountLinkService {

    private final AccountLinkRepository repository;
    private final GitHubClient gitHubClient;

    @Transactional
    public String link(Long discordUserId, String login) {
        GitHubUser user = gitHubClient.findUser(login)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 GitHub 아이디예요: " + login));

        repository.findByGithubUserId(user.id())
                .filter(link -> !link.getDiscordUserId().equals(discordUserId))
                .ifPresent(link -> { throw new IllegalStateException("이미 다른 사람이 연동한 GitHub 계정이에요."); });

        repository.findByDiscordUserId(discordUserId)
                .ifPresentOrElse(
                        link -> link.relink(user.id(), user.login()),           // 재연동
                        () -> repository.save(new AccountLink(discordUserId, user.id(), user.login()))
                );

        return user.login();
    }
}
