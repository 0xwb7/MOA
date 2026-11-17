package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;

public record DigestSendResult(
        String teamName,
        DigestType type,
        boolean sent,
        int itemCount,
        int messageCount,
        String failureReason
) {
}
