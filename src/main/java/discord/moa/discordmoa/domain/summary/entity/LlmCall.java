package discord.moa.discordmoa.domain.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * OpenAI HTTP 호출 시도 한 번의 기록. 재시도도 한 행씩 남긴다(재시도도 과금되므로).
 * 일일 호출 수·토큰 상한은 이 테이블로 계산한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_llm_call_created_at", columnList = "createdAt"))
public class LlmCall {

    private static final int FAILURE_REASON_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LlmCallPurpose purpose;

    @Column(nullable = false, length = 20)
    private String promptVersion;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(nullable = false)
    private int inputChars;

    private Integer inputTokens;

    private Integer outputTokens;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private boolean success;

    @Column(length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    @Lob
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    @Builder
    private LlmCall(Long teamId, LlmCallPurpose purpose, String promptVersion, String model, int inputChars,
                    Integer inputTokens, Integer outputTokens, long durationMs, boolean success,
                    String failureReason, String responseBody, Instant createdAt) {
        this.teamId = teamId;
        this.purpose = purpose;
        this.promptVersion = promptVersion;
        this.model = model;
        this.inputChars = inputChars;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.success = success;
        this.failureReason = failureReason != null && failureReason.length() > FAILURE_REASON_MAX_LENGTH
                ? failureReason.substring(0, FAILURE_REASON_MAX_LENGTH)
                : failureReason;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }
}
