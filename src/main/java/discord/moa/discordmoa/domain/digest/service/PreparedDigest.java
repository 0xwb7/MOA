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
        List<Item> items,
        List<Long> changeBatchIds   // 이 Digest에 담긴 모든 배치
) {

    public record Item(
            Long itemId,
            String text,
            List<Long> changeBatchIds   // FALLBACK 항목만 채운다. 항목이 전달되면 이 배치들을 SENT로 바꾼다
    ) {
    }
}
