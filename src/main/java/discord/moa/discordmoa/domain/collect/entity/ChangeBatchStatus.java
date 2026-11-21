package discord.moa.discordmoa.domain.collect.entity;

/**
 * COLLECTED → PREPARED(회차 Digest에 담김) → SENT. 발송 실패나 준비 취소 시 COLLECTED로 돌아간다.
 */
public enum ChangeBatchStatus {
    COLLECTED,
    PREPARED,
    SENT,
}
