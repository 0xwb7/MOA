package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("openai")
public record OpenAiProperties(
        String apiKey,              // 환경 변수 OPENAI_API_KEY로만 받는다. 비어 있으면 LLM 요약을 건너뛴다
        String baseUrl,
        String model,
        String reasoningEffort,     // 비우면 요청에서 뺀다
        Duration timeout,
        int maxRetries,
        int maxOutputTokens
) {
}
