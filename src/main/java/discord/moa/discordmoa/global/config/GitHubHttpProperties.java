package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * GitHub API 호출 타임아웃. (github.app-id 등 다른 github.* 설정은 기존처럼 @Value로 읽는다)
 */
@ConfigurationProperties("github")
public record GitHubHttpProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout
) {
}
