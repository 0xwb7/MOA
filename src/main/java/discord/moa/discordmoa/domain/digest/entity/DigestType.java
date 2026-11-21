package discord.moa.discordmoa.domain.digest.entity;

public enum DigestType {
    LLM,        // LLM 기능 단위 요약
    FALLBACK,   // 요약 실패 시 레포별 커밋 목록
    NO_CHANGE,
}
