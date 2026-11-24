package discord.moa.discordmoa.domain.summary.service;

import java.time.Instant;
import java.util.List;

/**
 * 이전 회차에 PM에게 이미 보고한 기능 항목. LLM 입력에 "[P{id}]"로 넣고, 응답의 relatedPreviousItemId가 이 id를 가리킨다.
 *
 * @param id           DigestItem ID
 * @param scheduledFor 보고한 회차
 * @param endpoints    이 항목의 API 주소 (프롬프트 v4부터 저장). 없으면 빈 목록
 */
public record PreviousItem(Long id, Instant scheduledFor, SummaryStatus status, String title, List<String> endpoints) {

    public PreviousItem {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public PreviousItem(Long id, Instant scheduledFor, SummaryStatus status, String title) {
        this(id, scheduledFor, status, title, List.of());
    }

    public PreviousItem withoutEndpoints() {
        return new PreviousItem(id, scheduledFor, status, title, List.of());
    }
}
