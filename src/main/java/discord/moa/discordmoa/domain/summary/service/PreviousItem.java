package discord.moa.discordmoa.domain.summary.service;

import java.time.Instant;

/**
 * 이전 회차에 PM에게 이미 보고한 기능 항목. LLM 입력에 "[P{id}]"로 넣고, 응답의 relatedPreviousItemId가 이 id를 가리킨다.
 *
 * @param id           DigestItem ID
 * @param scheduledFor 보고한 회차
 */
public record PreviousItem(Long id, Instant scheduledFor, SummaryStatus status, String title) {
}
