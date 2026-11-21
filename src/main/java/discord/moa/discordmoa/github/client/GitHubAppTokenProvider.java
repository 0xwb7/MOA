package discord.moa.discordmoa.github.client;

import discord.moa.discordmoa.github.dto.GitHubAccessToken;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GitHubAppTokenProvider {

    private static final Duration JWT_BACKDATE = Duration.ofSeconds(60);
    private static final Duration JWT_LIFETIME = Duration.ofSeconds(540);
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final Map<Long, GitHubAccessToken> tokens = new ConcurrentHashMap<>();

    private final String appId;
    private final PrivateKey privateKey;

    public GitHubAppTokenProvider(@Qualifier("gitHubRestClient") RestClient restClient,
                                  @Value("${github.app-id}") String appId,
                                  @Value("${github.private-key-path}") String privateKeyPath) {
        this.restClient = restClient;
        this.appId = appId;
        this.privateKey = loadPrivateKey(resolvePath(privateKeyPath));
    }

    static Path resolvePath(String path) {
        String trimmed = path.strip();
        if (trimmed.equals("~") || trimmed.startsWith("~/")) {
            return Path.of(System.getProperty("user.home") + trimmed.substring(1));
        }
        return Path.of(trimmed);
    }

    public String createAppJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(appId)
                .issuedAt(Date.from(now.minus(JWT_BACKDATE)))
                .expiration(Date.from(now.plus(JWT_LIFETIME)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String getInstallationToken(Long installationId) {
        return tokens.compute(installationId, (id, cached) ->
                cached != null && isFresh(cached) ? cached : issueInstallationToken(id)
        ).token();
    }

    /**
     * 캐시된 설치 토큰을 버린다. 설치가 해제되면 캐시된 토큰이 401을 받기 때문에, 새로 발급받아 원인을 확인할 때 쓴다.
     */
    public void invalidate(Long installationId) {
        tokens.remove(installationId);
    }

    private boolean isFresh(GitHubAccessToken token) {
        return token.expiresAt().isAfter(Instant.now().plus(REFRESH_BEFORE_EXPIRY));
    }

    private GitHubAccessToken issueInstallationToken(Long installationId) {
        GitHubAccessToken token;
        try {
            token = restClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .headers(headers -> GitHubHeaders.apply(headers, createAppJwt(), GitHubHeaders.JSON))
                    .retrieve()
                    .body(GitHubAccessToken.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new GitHubInstallationGoneException(installationId, e);
        }

        if (token == null || token.token() == null || token.expiresAt() == null) {
            throw new IllegalStateException("GitHub 설치 토큰 응답이 올바르지 않아요.");
        }
        return token;
    }

    private static PrivateKey loadPrivateKey(Path path) {
        String pem;
        try {
            pem = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "GitHub App 개인 키 파일을 읽을 수 없어요: " + path.toAbsolutePath()
                            + " (github.private-key-path 설정을 확인해 주세요.)", e);
        }

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "GitHub App 개인 키가 PKCS#1 형식이에요. "
                            + "openssl pkcs8 -topk8 -nocrypt -in <원본>.pem -out <변환>.pem 으로 PKCS#8로 변환해 주세요.");
        }

        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("GitHub App 개인 키를 해석할 수 없어요. PKCS#8 PEM 형식인지 확인해 주세요.", e);
        }
    }
}
