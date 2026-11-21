package discord.moa.discordmoa.domain.ops;

import java.time.Instant;

/**
 * 운영진에게 알려야 하는 상황.
 *
 * @param scheduledFor 회차 (발송 예정 시각)
 */
public record OpsEvent(
        Type type,
        String teamName,
        Instant scheduledFor,
        String detail
) {

    public enum Type {
        PREPARE_FAILED("준비 실패"),
        PREPARE_DEADLINE_FALLBACK("준비 마감 초과로 변경 목록 발송"),
        SEND_INLINE_FALLBACK("발송 시점 즉석 변경 목록 발송"),
        SEND_FAILED("발송 실패"),
        RECOVERY_LATE_SEND("재시작 복구: 늦은 발송"),
        RECOVERY_ABANDONED("재시작 복구: 발송 포기");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
