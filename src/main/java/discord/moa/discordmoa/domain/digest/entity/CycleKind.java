package discord.moa.discordmoa.domain.digest.entity;

public enum CycleKind {
    SCHEDULED,  // 정기 발송 (오전 9시, 오후 9시)
    MANUAL,     // /발송테스트
}
