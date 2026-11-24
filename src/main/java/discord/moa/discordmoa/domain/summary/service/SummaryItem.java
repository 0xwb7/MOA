package discord.moa.discordmoa.domain.summary.service;

import java.util.List;

/**
 * @param relatedPreviousItemId 이 항목이 상태를 올리거나 이어받은 이전 항목 ID (입력의 [P번호]). 없으면 null
 * @param endpoints             정규화한 API 주소("METHOD /path"). 서버 쪽은 만든·구현한 주소, 화면 쪽은 호출하는 주소.
 *                              없거나 프롬프트 v4 이전이면 빈 목록
 */
public record SummaryItem(
        SummaryStatus status,
        String title,
        List<String> commitShas,
        Long relatedPreviousItemId,
        List<String> endpoints
) {

    public SummaryItem {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public SummaryItem(SummaryStatus status, String title, List<String> commitShas) {
        this(status, title, commitShas, null, List.of());
    }

    public SummaryItem(SummaryStatus status, String title, List<String> commitShas, Long relatedPreviousItemId) {
        this(status, title, commitShas, relatedPreviousItemId, List.of());
    }
}
