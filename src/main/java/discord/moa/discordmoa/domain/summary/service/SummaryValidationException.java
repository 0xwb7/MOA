package discord.moa.discordmoa.domain.summary.service;

/**
 * LLM 응답이 스키마나 검증 규칙을 어긴 경우. 재시도하지 않고 fallback으로 보낸다.
 */
public class SummaryValidationException extends RuntimeException {

    public SummaryValidationException(String message) {
        super(message);
    }

    public SummaryValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
