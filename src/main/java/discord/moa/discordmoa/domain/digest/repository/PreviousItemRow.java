package discord.moa.discordmoa.domain.digest.repository;

import java.time.Instant;
import java.util.List;

/**
 * 이전 회차에 PM에게 보고한 LLM 항목 한 줄 (조회용).
 */
public record PreviousItemRow(Long id, Instant scheduledFor, String itemStatus, String title, List<String> endpoints) {

    public PreviousItemRow {
        endpoints = endpoints == null ? List.of() : endpoints;
    }
}
