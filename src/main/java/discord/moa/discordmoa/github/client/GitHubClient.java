package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.github.dto.GitHubInstallation;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import discord.moa.discordmoa.github.dto.GitHubUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GitHubClient {

    private final RestClient restClient = RestClient.create("https://api.github.com");
    private final GitHubAppTokenProvider tokenProvider;

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

    public Optional<Long> findInstallationId(String owner, String repo) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/repos/{owner}/{repo}/installation", owner, repo)
                            .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.createAppJwt(), GitHubHeaders.JSON))
                            .retrieve()
                            .body(GitHubInstallation.class)
            ).map(GitHubInstallation::id);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public Optional<GitHubRepo> findRepo(Long installationId, String owner, String repo) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/repos/{owner}/{repo}", owner, repo)
                            .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.JSON))
                            .retrieve()
                            .body(GitHubRepo.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public Optional<String> findHeadSha(Long installationId, String owner, String repo, String branch) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/repos/{owner}/{repo}/commits/{branch}", owner, repo, branch)
                            .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.SHA))
                            .retrieve()
                            .body(String.class)
            ).map(String::strip);
        } catch (HttpClientErrorException.Conflict e) {
            return Optional.empty();
        }
    }
}
