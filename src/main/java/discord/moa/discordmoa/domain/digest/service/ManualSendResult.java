package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;

public record ManualSendResult(
        TeamCollectResult collected,
        DigestSendResult sent
) {
}
