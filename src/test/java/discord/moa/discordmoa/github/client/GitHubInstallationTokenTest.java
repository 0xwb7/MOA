package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.global.config.GitHubConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubInstallationTokenTest {

    private static final String TOKEN_JSON = "{\"token\":\"ghs_test\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}";

    @TempDir
    Path tempDir;

    @Test
    void 설치_토큰_발급이_404면_설치_해제_예외를_던진다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) ->
                LocalHttpServer.Response.json(404, "{\"message\":\"Not Found\"}"))) {
            GitHubAppTokenProvider provider = provider(server);

            assertThatThrownBy(() -> provider.getInstallationToken(555L))
                    .isInstanceOfSatisfying(GitHubInstallationGoneException.class,
                            e -> assertThat(e.getInstallationId()).isEqualTo(555L));
        }
    }

    @Test
    void 토큰은_캐시하고_invalidate하면_새로_발급받는다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) -> LocalHttpServer.Response.json(201, TOKEN_JSON))) {
            GitHubAppTokenProvider provider = provider(server);

            assertThat(provider.getInstallationToken(555L)).isEqualTo("ghs_test");
            provider.getInstallationToken(555L);
            assertThat(server.requests).containsExactly("POST /app/installations/555/access_tokens");

            provider.invalidate(555L);
            provider.getInstallationToken(555L);
            assertThat(server.requests).hasSize(2);
        }
    }

    private GitHubAppTokenProvider provider(LocalHttpServer server) throws Exception {
        Path key = tempDir.resolve("key.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        return new GitHubAppTokenProvider(
                GitHubConfig.gitHubRestClient(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)),
                "12345", key.toString());
    }
}
