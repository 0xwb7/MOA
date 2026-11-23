package discord.moa.discordmoa.domain.digest.repository;

import java.time.Instant;

/**
 * 이전 회차에 PM에게 보고한 LLM 항목 한 줄 (조회용).
 */
public record PreviousItemRow(Long id, Instant scheduledFor, String itemStatus, String title) {
}
