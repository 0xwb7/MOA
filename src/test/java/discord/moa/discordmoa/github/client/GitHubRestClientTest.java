package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.global.config.GitHubConfig;
import discord.moa.discordmoa.global.config.GitHubHttpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GitHubRestClientTest {

    @TempDir
    Path tempDir;

    @Test
    void GitHub가_응답을_늦게_주면_응답_타임아웃으로_끊는다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) ->
                new LocalHttpServer.Response(200, "{\"id\":1,\"login\":\"alice\"}", 2000))) {
            RestClient restClient = GitHubConfig.gitHubRestClient(server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(300));
            GitHubClient client = new GitHubClient(restClient, mock(GitHubAppTokenProvider.class));

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.findUser("alice")).isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        }
    }

    @Test
    void 응답이_제시간에_오면_정상_처리한다() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer((method, path) ->
                LocalHttpServer.Response.json(200, "{\"id\":1,\"login\":\"alice\"}"))) {
            RestClient restClient = GitHubConfig.gitHubRestClient(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThat(new GitHubClient(restClient, mock(GitHubAppTokenProvider.class)).findUser("alice"))
                    .hasValueSatisfying(user -> assertThat(user.login()).isEqualTo("alice"));
        }
    }

    @Test
    void 타임아웃_설정은_application_yml에서_읽고_기본값이_있다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class);

        runner.run(context -> assertThat(context.getBean(GitHubHttpProperties.class))
                .isEqualTo(new GitHubHttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(15))));
        runner.withPropertyValues("github.connect-timeout=2s", "github.read-timeout=30s")
                .run(context -> assertThat(context.getBean(GitHubHttpProperties.class))
                        .isEqualTo(new GitHubHttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(30))));
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(GitHubHttpProperties.class).readTimeout())
                        .isEqualTo(Duration.ofSeconds(15)));
    }

    @Test
    void GitHubClient와_토큰_발급기가_같은_GitHub_RestClient_빈을_쓴다() throws Exception {
        Path key = tempDir.resolve("key.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");

        new ApplicationContextRunner()
                .withUserConfiguration(GitHubConfig.class)
                .withBean(GitHubAppTokenProvider.class)
                .withBean(GitHubClient.class)
                .withBean("otherRestClient", RestClient.class, RestClient::create)   // 다른 RestClient가 있어도 구분돼야 한다
                .withPropertyValues("github.app-id=1", "github.private-key-path=" + key)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RestClient gitHub = context.getBean("gitHubRestClient", RestClient.class);
                    assertThat(context.getBean(GitHubClient.class)).extracting("restClient").isSameAs(gitHub);
                    assertThat(context.getBean(GitHubAppTokenProvider.class)).extracting("restClient").isSameAs(gitHub);
                });
    }

    @EnableConfigurationProperties(GitHubHttpProperties.class)
    static class PropertiesOnly {
    }
}
