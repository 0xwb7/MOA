package discord.moa.discordmoa.domain.digest.service;

import java.time.Instant;

/**
 * "확인했어요" 버튼 처리 결과.
 *
 * @param confirmedAt CONFIRMED, ALREADY_CONFIRMED일 때 확인 시각
 */
public record ConfirmCheck(Status status, Instant confirmedAt) {

    public enum Status {
        CONFIRMABLE,        // 확인할 수 있음 (아직 기록 전)
        CONFIRMED,          // 이번 클릭으로 기록함
        ALREADY_CONFIRMED,  // 이미 확인된 항목
        NOT_PM,             // 누른 사람이 그 팀의 현재 PM이 아님
        NOT_FOUND,
    }

    static ConfirmCheck of(Status status) {
        return new ConfirmCheck(status, null);
    }
}
