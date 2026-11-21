package discord.moa.discordmoa.github.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.SECONDS;

class GitHubAppTokenProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void PKCS8_키로_RS256_App_JWT를_만든다() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path keyFile = writePem(keyPair, "PRIVATE KEY");
        GitHubAppTokenProvider provider = new GitHubAppTokenProvider(RestClient.create(), "12345", keyFile.toString());

        Instant now = Instant.now();
        String jwt = provider.createAppJwt();

        var parsed = Jwts.parser().verifyWith(keyPair.getPublic()).build().parseSignedClaims(jwt);
        Claims claims = parsed.getPayload();
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(claims.getIssuer()).isEqualTo("12345");
        assertThat(claims.getIssuedAt().toInstant()).isCloseTo(now.minusSeconds(60), within(2, SECONDS));
        assertThat(claims.getExpiration().toInstant()).isCloseTo(now.plusSeconds(540), within(2, SECONDS));
    }

    @Test
    void 키_경로의_물결표는_홈_디렉터리로_바꾼다() {
        String home = System.getProperty("user.home");

        assertThat(GitHubAppTokenProvider.resolvePath("~/keys/app.pem")).isEqualTo(Path.of(home, "keys", "app.pem"));
        assertThat(GitHubAppTokenProvider.resolvePath(" /abs/app.pem ")).isEqualTo(Path.of("/abs/app.pem"));
        assertThat(GitHubAppTokenProvider.resolvePath("~other/app.pem")).isEqualTo(Path.of("~other/app.pem"));
    }

    @Test
    void 키_파일이_없으면_절대_경로를_알려준다() {
        assertThatThrownBy(() -> new GitHubAppTokenProvider(RestClient.create(), "12345", tempDir.resolve("missing.pem").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(tempDir.resolve("missing.pem").toString());
    }

    @Test
    void PKCS1_키면_변환_방법을_안내한다() throws Exception {
        Path keyFile = tempDir.resolve("pkcs1.pem");
        Files.writeString(keyFile, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> new GitHubAppTokenProvider(RestClient.create(), "12345", keyFile.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    private Path writePem(KeyPair keyPair, String type) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        Path file = tempDir.resolve("key.pem");
        Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
        return file;
    }
}
