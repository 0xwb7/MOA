package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;

/**
 * READY로 저장한 회차.
 *
 * @param fallbackReason FALLBACK일 때 LLM 요약 대신 변경 목록을 쓴 이유. 그 밖에는 null
 */
public record CompletedDigest(DigestType type, String fallbackReason) {
}
