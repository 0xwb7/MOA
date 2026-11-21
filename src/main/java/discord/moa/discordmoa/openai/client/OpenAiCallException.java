package discord.moa.discordmoa.openai.client;

import lombok.Getter;

import java.time.Duration;

/**
 * OpenAI 호출이 2xx 응답을 받지 못한 경우.
 */
@Getter
public class OpenAiCallException extends RuntimeException {

    public enum Kind {
        TIMEOUT(true),
        NETWORK(true),
        RATE_LIMIT(true),
        SERVER(true),
        QUOTA(false),           // 429 insufficient_quota: 잔액·한도 부족이라 다시 해도 안 됨
        CLIENT(false);          // 400, 401, 403, 404 등

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }

    private final Kind kind;
    private final Integer status;
    private final String responseBody;
    private final Duration retryAfter;

    public OpenAiCallException(Kind kind, Integer status, String responseBody, Duration retryAfter,
                               String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.responseBody = responseBody;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return kind.retryable;
    }

    public static OpenAiCallException fromStatus(int status, String body, Duration retryAfter) {
        Kind kind;
        if (status == 429) {
            kind = body != null && body.contains("insufficient_quota") ? Kind.QUOTA : Kind.RATE_LIMIT;
        } else if (status >= 500) {
            kind = Kind.SERVER;
        } else {
            kind = Kind.CLIENT;
        }
        return new OpenAiCallException(kind, status, body, retryAfter, "OpenAI가 HTTP " + status + " 오류를 반환했어요", null);
    }
}
