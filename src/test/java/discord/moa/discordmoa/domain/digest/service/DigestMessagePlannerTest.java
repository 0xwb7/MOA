package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.service.DigestMessagePlanner.PlannedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DigestMessagePlannerTest {

    private static final String HEADER = "<@7> 헤더";
    private final DigestMessagePlanner planner = new DigestMessagePlanner();

    @Test
    void 한도_안이면_메시지_하나에_헤더와_항목을_순서대로_담는다() {
        List<DigestMessage> messages = planner.plan(HEADER, 7L, List.of(item(1L, "a"), item(2L, "b")));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().mentionUserId()).isEqualTo(7L);
        assertThat(messages.getFirst().blocks()).containsExactly(
                DigestBlock.textOnly(HEADER), DigestBlock.item(1L, "a"), DigestBlock.item(2L, "b"));
    }

    @Test
    void 컴포넌트_40개를_넘으면_나누고_멘션과_헤더는_첫_메시지에만_넣는다() {
        // 헤더 1 + 항목당 5 → 첫 메시지에 7개(36), 8번째부터 다음 메시지
        List<PlannedItem> items = LongStream.rangeClosed(1, 8).mapToObj(id -> item(id, "t" + id)).toList();

        List<DigestMessage> messages = planner.plan(HEADER, 7L, items);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).blocks()).hasSize(1 + 7);
        assertThat(messages.get(0).mentionUserId()).isEqualTo(7L);
        assertThat(messages.get(1).blocks()).containsExactly(DigestBlock.item(8L, "t8"));
        assertThat(messages.get(1).mentionUserId()).isNull();
        assertThat(messages.stream().flatMap(m -> m.blocks().stream()).filter(b -> b.text().equals(HEADER))).hasSize(1);
    }

    @Test
    void 텍스트_합계가_4000자를_넘으면_나눈다() {
        String big = "x".repeat(2500);

        List<DigestMessage> messages = planner.plan(HEADER, 7L, List.of(item(1L, big), item(2L, big), item(3L, big)));

        assertThat(messages).hasSize(3);
        messages.forEach(message -> assertThat(message.blocks().stream().mapToInt(b -> b.text().length()).sum())
                .isLessThanOrEqualTo(DigestMessagePlanner.MAX_TEXT_LENGTH));
        assertThat(messages.get(1).mentionUserId()).isNull();
    }

    @Test
    void 항목_하나가_한도를_넘으면_예외() {
        assertThatThrownBy(() -> planner.plan(HEADER, 7L, List.of(item(1L, "x".repeat(4001)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 안내가_있으면_마지막_메시지_끝에_버튼_없이_붙인다() {
        List<DigestMessage> messages = planner.plan(HEADER, 7L, List.of(item(1L, "a")), "⚠️ 안내");

        assertThat(messages).singleElement().satisfies(message -> assertThat(message.blocks()).containsExactly(
                DigestBlock.textOnly(HEADER), DigestBlock.item(1L, "a"), DigestBlock.textOnly("⚠️ 안내")));
    }

    @Test
    void 마지막_메시지에_안내_자리가_없으면_안내만_담은_메시지를_더_만든다() {
        // 텍스트 합계가 4000자를 넘게 만든다
        String big = "x".repeat(3990);

        List<DigestMessage> messages = planner.plan(HEADER, 7L, List.of(item(1L, big)), "⚠️ 안내");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).blocks()).containsExactly(DigestBlock.textOnly(HEADER), DigestBlock.item(1L, big));
        assertThat(messages.get(1).blocks()).containsExactly(DigestBlock.textOnly("⚠️ 안내"));
        assertThat(messages.get(1).mentionUserId()).isNull();
    }

    @Test
    void 컴포넌트가_모자라면_안내를_다음_메시지로_넘긴다() {
        // 첫 메시지: 헤더 1 + 항목 7개 = 36, 둘째 메시지: 항목 8개 = 40 → 안내(2)는 셋째 메시지로
        List<PlannedItem> items = LongStream.rangeClosed(1, 15).mapToObj(id -> item(id, "t" + id)).toList();

        List<DigestMessage> messages = planner.plan(HEADER, 7L, items, "⚠️ 안내");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).blocks()).hasSize(8);
        assertThat(messages.get(2).blocks()).containsExactly(DigestBlock.textOnly("⚠️ 안내"));
    }

    @Test
    void 안내가_없으면_기존과_같다() {
        assertThat(planner.plan(HEADER, 7L, List.of(item(1L, "a")), null))
                .isEqualTo(planner.plan(HEADER, 7L, List.of(item(1L, "a"))));
    }

    private static PlannedItem item(Long id, String text) {
        return new PlannedItem(id, text);
    }
}
