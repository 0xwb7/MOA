package discord.moa.discordmoa.github.client;

import org.springframework.http.HttpHeaders;

final class GitHubHeaders {

    static final String JSON = "application/vnd.github+json";
    static final String SHA = "application/vnd.github.sha";

    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";

    private GitHubHeaders() {
    }

    static void apply(HttpHeaders headers, String bearerToken, String accept) {
        headers.setBearerAuth(bearerToken);
        headers.set(HttpHeaders.ACCEPT, accept);
        headers.set(API_VERSION_HEADER, API_VERSION);
    }
}
