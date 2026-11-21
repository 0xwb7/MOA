package discord.moa.discordmoa.domain.summary.service;

/**
 * 한 번의 요약에 대한 집계. 토큰과 소요 시간은 재시도를 포함한 합계다.
 */
public record SummaryStats(
        String promptVersion,
        String model,
        int inputChars,
        int inputTokens,
        int outputTokens,
        long durationMs,
        int attempts
) {
}
