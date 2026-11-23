package discord.moa.discordmoa.domain.summary.service;

import java.util.List;

/**
 * @param relatedPreviousItemId 이 항목이 상태를 올리거나 이어받은 이전 항목 ID (입력의 [P번호]). 없으면 null
 */
public record SummaryItem(
        SummaryStatus status,
        String title,
        List<String> commitShas,
        Long relatedPreviousItemId
) {

    public SummaryItem(SummaryStatus status, String title, List<String> commitShas) {
        this(status, title, commitShas, null);
    }
}
