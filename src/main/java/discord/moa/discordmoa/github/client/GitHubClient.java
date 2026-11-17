package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.github.dto.GitHubUser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class GitHubClient {

    private final RestClient restClient = RestClient.create("https://api.github.com");

    public Optional<GitHubUser> findUser(String login) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/users/{login}", login)
                            .retrieve()
                            .body(GitHubUser.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
