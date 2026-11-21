package discord.moa.discordmoa.domain.digest.entity;

/**
 * PREPARING → READY → SENT / FAILED
 */
public enum DigestStatus {
    PREPARING,  // 회차를 선점하고 수집·요약 중
    READY,      // 발송할 내용(헤더, 멘션, 항목)이 DB에 저장됨
    SENT,
    FAILED,
}
