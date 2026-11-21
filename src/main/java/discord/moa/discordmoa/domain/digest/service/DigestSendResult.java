package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;

public record DigestSendResult(
        String teamName,
        DigestType type,
        boolean sent,
        int itemCount,
        int messageCount,
        String failureReason,
        String summaryFallbackReason    // 변경이 있었는데 LLM 요약을 못 쓴 사유. LLM이거나 변경이 없으면 null
) {
}
