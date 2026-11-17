package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;

import java.util.List;

public record PreparedDigest(
        Long digestId,
        DigestType type,
        String teamName,
        Long channelId,
        Long pmDiscordUserId,
        String headerText,
        List<Item> items
) {

    public record Item(
            Long itemId,
            String text,
            List<Long> changeBatchIds
    ) {
    }
}
