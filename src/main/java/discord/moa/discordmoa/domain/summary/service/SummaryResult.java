package discord.moa.discordmoa.domain.summary.service;

import java.util.List;

/**
 * @param changeBatchIds 요약 대상이었던 COLLECTED 배치. 비어 있으면 요약할 변경이 없었다는 뜻
 * @param items          성공했을 때만 채워진다. READY/PARTIAL이 앞, INTERNAL이 맨 뒤
 * @param fallbackReason 실패 사유. null이면 성공
 * @param limitReached   이 용도의 일일 상한에 걸려 LLM을 부르지 않았는지
 */
public record SummaryResult(
        List<Long> changeBatchIds,
        List<SummaryItem> items,
        String fallbackReason,
        SummaryStats stats,
        boolean limitReached
) {

    public static SummaryResult noChanges() {
        return new SummaryResult(List.of(), List.of(), null, null, false);
    }

    public static SummaryResult success(List<Long> changeBatchIds, List<SummaryItem> items, SummaryStats stats) {
        return new SummaryResult(changeBatchIds, items, null, stats, false);
    }

    public static SummaryResult fallback(List<Long> changeBatchIds, String reason, SummaryStats stats) {
        return new SummaryResult(changeBatchIds, List.of(), reason, stats, false);
    }

    public static SummaryResult limitReached(List<Long> changeBatchIds, String reason, SummaryStats stats) {
        return new SummaryResult(changeBatchIds, List.of(), reason, stats, true);
    }

    public boolean hasChanges() {
        return !changeBatchIds.isEmpty();
    }

    public boolean succeeded() {
        return hasChanges() && fallbackReason == null;
    }
}
