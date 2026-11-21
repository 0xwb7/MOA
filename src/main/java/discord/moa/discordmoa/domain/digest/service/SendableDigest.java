package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.DigestType;

import java.time.Instant;
import java.util.List;

/**
 * DB에 저장된 READY 회차. 발송 시점에 이 내용으로 메시지를 다시 나눈다.
 */
public record SendableDigest(
        Long digestId,
        Long teamId,
        String teamName,
        Long channelId,         // 발송 시점의 팀 채널
        Instant scheduledFor,
        CycleKind cycleKind,
        DigestType type,
        String headerText,
        Long mentionUserId,
        String fallbackReason,
        List<Item> items
) {

    public record Item(Long itemId, String text) {
    }
}
