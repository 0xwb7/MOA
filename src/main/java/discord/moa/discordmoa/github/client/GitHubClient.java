package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.github.dto.GitHubCommit;
import discord.moa.discordmoa.github.dto.GitHubCommitPage;
import discord.moa.discordmoa.github.dto.GitHubCompare;
import discord.moa.discordmoa.github.dto.GitHubInstallation;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import discord.moa.discordmoa.github.dto.GitHubUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Component
public class GitHubClient {

    private final RestClient restClient;
    private final GitHubAppTokenProvider tokenProvider;

    public GitHubClient(@Qualifier("gitHubRestClient") RestClient restClient, GitHubAppTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
    }

    public void forgetInstallationToken(Long installationId) {
        tokenProvider.invalidate(installationId);
    }

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

    public Optional<GitHubCompare> compare(Long installationId, String owner, String repo, String base, String head) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/repos/{owner}/{repo}/compare/{base}...{head}", owner, repo, base, head)
                            .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.JSON))
                            .retrieve()
                            .body(GitHubCompare.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * ref(커밋 SHA) 시점의 파일 내용. 파일이 없으면(404) 빈 값.
     * path는 "/"로 나눠 세그먼트마다 인코딩한다 (파일 경로의 "/"가 %2F로 바뀌지 않게).
     */
    public Optional<String> findFileContent(Long installationId, String owner, String repo, String path, String ref) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri(builder -> builder.pathSegment("repos", owner, repo, "contents")
                                    .pathSegment(path.split("/"))
                                    .queryParam("ref", ref)
                                    .build())
                            .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.RAW))
                            .retrieve()
                            .body(String.class)
            );
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public GitHubCommitPage listCommits(Long installationId, String owner, String repo, String sha, int perPage) {
        try {
            ResponseEntity<List<GitHubCommit>> response = restClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/commits")
                            .queryParam("sha", sha)
                            .queryParam("per_page", perPage)
                            .build(owner, repo))
                    .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.JSON))
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });

            List<GitHubCommit> commits = response.getBody() == null ? List.of() : response.getBody();
            String link = response.getHeaders().getFirst(HttpHeaders.LINK);
            return new GitHubCommitPage(commits, link != null && link.contains("rel=\"next\""));
        } catch (HttpClientErrorException.Conflict e) {
            return new GitHubCommitPage(List.of(), false);
        }
    }

    public GitHubCommit findCommit(Long installationId, String owner, String repo, String sha) {
        GitHubCommit commit = restClient.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                .headers(headers -> GitHubHeaders.apply(headers, tokenProvider.getInstallationToken(installationId), GitHubHeaders.JSON))
                .retrieve()
                .body(GitHubCommit.class);

        if (commit == null) {
            throw new IllegalStateException("GitHub 커밋 상세 응답이 비어 있어요: " + owner + "/" + repo + "@" + sha);
        }
        return commit;
    }
}
