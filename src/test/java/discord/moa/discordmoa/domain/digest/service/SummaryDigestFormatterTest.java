package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryDigestFormatterTest {

    private final SummaryDigestFormatter formatter = new SummaryDigestFormatter();

    @Test
    void 범례는_기능_완성_일부만_됐어요_내부_작업이다() {
        assertThat(formatter.header("1팀", 7L)).isEqualTo("""
                <@7> 지난 12시간 동안 '1팀'에 이런 변경이 있었어요
                -# 🟢  기능 완성    🟡  일부만 됐어요    ⚪  내부 작업""");
    }

    @Test
    void 항목은_상태_이모지와_제목이다() {
        assertThat(formatter.itemText(new SummaryItem(SummaryStatus.READY, "로그인 기능이 완성됐어요", List.of("a"))))
                .isEqualTo("🟢 로그인 기능이 완성됐어요");
    }
}
