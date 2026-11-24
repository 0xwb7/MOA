package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.global.config.GitHubConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class GitHubFileContentTest {

    private static final String SOURCE = "@RestController\n@RequestMapping(\"/api/auth\")\nclass AuthController {}";

    @Test
    void ref_시점의_파일_내용을_raw로_받고_경로는_세그먼트별로_보낸다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) -> LocalHttpServer.Response.json(200, SOURCE))) {
            GitHubClient client = client(server);

            assertThat(client.findFileContent(1L, "team1", "backend",
                    "src/main/java/moa/auth/AuthController.java", "abc123")).contains(SOURCE);

            assertThat(server.rawUris).containsExactly(
                    "/repos/team1/backend/contents/src/main/java/moa/auth/AuthController.java?ref=abc123");
            assertThat(server.acceptHeaders).containsExactly("application/vnd.github.raw+json");
        }
    }

    @Test
    void 경로의_공백은_인코딩하고_슬래시는_그대로_둔다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) -> LocalHttpServer.Response.json(200, SOURCE))) {
            client(server).findFileContent(1L, "team1", "backend", "src/my dir/A.kt", "abc123");

            assertThat(server.rawUris).containsExactly("/repos/team1/backend/contents/src/my%20dir/A.kt?ref=abc123");
        }
    }

    @Test
    void 파일이_없으면_빈_값이다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) ->
                LocalHttpServer.Response.json(404, "{\"message\":\"Not Found\"}"))) {
            assertThat(client(server).findFileContent(1L, "team1", "backend", "src/A.java", "abc123")).isEmpty();
        }
    }

    private static GitHubClient client(LocalHttpServer server) {
        RestClient restClient = GitHubConfig.gitHubRestClient(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1));
        GitHubAppTokenProvider tokens = mock(GitHubAppTokenProvider.class);
        given(tokens.getInstallationToken(1L)).willReturn("installation-token");
        return new GitHubClient(restClient, tokens);
    }
}
