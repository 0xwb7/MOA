package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryPreview;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.summary.service.SummaryStats;
import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryPreviewMessageTest {

    private static final SummaryStats STATS = new SummaryStats("v1", "gpt-6.1-terra", 12345, 4000, 300, 6200, 1);

    @Test
    void 항목을_보여주고_통계는_보여주지_않는다() {
        SummaryResult result = SummaryResult.success(List.of(1L), List.of(
                new SummaryItem(SummaryStatus.READY, "로그인 기능이 완성됐어요", List.of("a", "b")),
                new SummaryItem(SummaryStatus.INTERNAL, "내부 작업", List.of("c"))), STATS);

        String text = SummaryPreviewMessage.format(new SummaryPreview("1팀", result));

        assertThat(text).isEqualTo("""
                🔎 '1팀' 요약 미리보기 (발송·상태 변경 없음)

                🟢 로그인 기능이 완성됐어요 (커밋 2개)
                ⚪ 내부 작업 (커밋 1개)
                """);
    }

    @Test
    void 이전_항목에서_이어진_항목은_운영진에게_표시한다() {
        SummaryResult result = SummaryResult.success(List.of(1L), List.of(
                new SummaryItem(SummaryStatus.READY, "로그인 기능이 완성됐어요", List.of("a"), 12L),
                new SummaryItem(SummaryStatus.PARTIAL, "회원가입 서버 준비", List.of("b"))), STATS);

        assertThat(SummaryPreviewMessage.format(new SummaryPreview("1팀", result)))
                .contains("🟢 로그인 기능이 완성됐어요 (커밋 1개)\n   ↗ 이전 항목 #12에서 이어짐\n🟡 회원가입 서버 준비 (커밋 1개)\n");
    }

    @Test
    void 실패하면_사유를_보여준다() {
        SummaryResult result = SummaryResult.fallback(List.of(1L), "오늘 LLM 호출 상한(40회)에 도달했어요",
                new SummaryStats("v1", "gpt-6.1-terra", 0, 0, 0, 0, 0));

        assertThat(SummaryPreviewMessage.format(new SummaryPreview("1팀", result)))
                .contains("⚠️ 요약을 만들지 못했어요: 오늘 LLM 호출 상한(40회)에 도달했어요", "fallback")
                .doesNotContain("시도 0회");
    }

    @Test
    void 미리보기_상한에_걸리면_정기_발송과_별개라고_알려준다() {
        SummaryResult result = SummaryResult.limitReached(List.of(1L), "오늘 미리보기용 LLM 호출 상한(30회)에 도달했어요",
                new SummaryStats("v1", "gpt-6.1-terra", 0, 0, 0, 0, 0));

        assertThat(SummaryPreviewMessage.format(new SummaryPreview("1팀", result)))
                .contains("⚠️ 오늘 미리보기용 LLM 호출 상한(30회)에 도달했어요", "정기 발송에는 영향이 없어요")
                .doesNotContain("fallback");
    }

    @Test
    void 변경이_없으면_수집을_안내한다() {
        assertThat(SummaryPreviewMessage.format(new SummaryPreview("1팀", SummaryResult.noChanges())))
                .contains("/수집테스트");
    }

    @Test
    void 이천자를_넘지_않게_자른다() {
        List<SummaryItem> items = IntStream.range(0, 30)
                .mapToObj(i -> new SummaryItem(SummaryStatus.PARTIAL, "기능 " + i + " " + "가".repeat(150), List.of("a")))
                .toList();

        String text = SummaryPreviewMessage.format(new SummaryPreview("1팀", SummaryResult.success(List.of(1L), items, STATS)));

        assertThat(text.length()).isLessThanOrEqualTo(2000);
        assertThat(text).contains("… 외 ").doesNotContain("-# 입력");
    }
}
