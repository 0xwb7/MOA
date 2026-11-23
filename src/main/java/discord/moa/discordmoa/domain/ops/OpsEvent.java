package discord.moa.discordmoa.domain.ops;

import java.time.Instant;

/**
 * 운영진에게 알려야 하는 상황.
 *
 * @param teamName     없으면 null (시작, 테스트 알림)
 * @param scheduledFor 회차 (발송 예정 시각). 없으면 null
 */
public record OpsEvent(
        Type type,
        String teamName,
        Instant scheduledFor,
        String detail
) {

    public enum Severity {
        FAILURE("🔴"),   // 실패: 운영진 역할 멘션
        WARNING("🟡"),   // 주의: 발송은 됐지만 품질이 떨어졌거나 늦음
        INFO("🟢");      // 정보

        private final String emoji;

        Severity(String emoji) {
            this.emoji = emoji;
        }

        public String emoji() {
            return emoji;
        }
    }

    public enum Type {
        PREPARE_FAILED("준비 실패", Severity.FAILURE),
        PREPARE_DEADLINE_FALLBACK("준비 마감 초과로 변경 목록 발송", Severity.WARNING),
        COLLECT_FAILED("정기 준비 중 레포 수집 실패", Severity.WARNING),
        SUMMARY_FALLBACK("LLM 요약 실패로 변경 목록 발송", Severity.WARNING),
        SEND_INLINE_FALLBACK("발송 시점 즉석 변경 목록 발송", Severity.WARNING),
        SEND_FAILED("발송 실패", Severity.FAILURE),
        RECOVERY_LATE_SEND("재시작 복구: 늦은 발송", Severity.WARNING),
        RECOVERY_ABANDONED("재시작 복구: 발송 포기", Severity.FAILURE),
        REPO_DEACTIVATED("레포 해제", Severity.INFO),
        STARTED("MOA 시작", Severity.INFO),
        OPS_TEST("운영 알림 테스트", Severity.INFO);

        private final String label;
        private final Severity severity;

        Type(String label, Severity severity) {
            this.label = label;
            this.severity = severity;
        }

        public String label() {
            return label;
        }

        public Severity severity() {
            return severity;
        }
    }

    public static OpsEvent of(Type type, String detail) {
        return new OpsEvent(type, null, null, detail);
    }
}
