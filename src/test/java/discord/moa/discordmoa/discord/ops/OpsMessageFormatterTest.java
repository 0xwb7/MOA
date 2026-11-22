package discord.moa.discordmoa.discord.ops;

import discord.moa.discordmoa.domain.ops.OpsEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMessageFormatterTest {

    private static final Long ROLE_ID = 555L;
    private static final Instant CYCLE = Instant.parse("2026-11-27T00:00:00Z");   // 11-27 09:00 KST

    @Test
    void 심각도_유형_팀_회차_상세를_보여준다() {
        OpsEvent event = new OpsEvent(OpsEvent.Type.SEND_FAILED, "1팀", CYCLE, "봇에게 #1팀 채널의 Send Messages 권한이 없어요.");

        assertThat(OpsMessageFormatter.text(event, null)).isEqualTo("""
                🔴 **발송 실패**
                팀: 1팀 · 회차: 11-27 09:00
                봇에게 #1팀 채널의 Send Messages 권한이 없어요.""");
    }

    @Test
    void 실패_알림만_운영진_역할을_멘션한다() {
        MessageCreateData failure = OpsMessageFormatter.format(
                new OpsEvent(OpsEvent.Type.SEND_FAILED, "1팀", CYCLE, "원인"), ROLE_ID);

        assertThat(failure.getContent()).startsWith("🔴 **발송 실패** <@&555>");
        assertThat(failure.getMentionedRoles()).containsExactly("555");
        assertThat(failure.getAllowedMentions()).isEmpty();   // 역할 외 멘션(@everyone 등)은 막는다
    }

    @ParameterizedTest
    @EnumSource(value = OpsEvent.Type.class, names = {"SUMMARY_FALLBACK", "COLLECT_FAILED", "STARTED", "OPS_TEST"})
    void 주의와_정보_알림은_멘션하지_않는다(OpsEvent.Type type) {
        MessageCreateData data = OpsMessageFormatter.format(new OpsEvent(type, "1팀", CYCLE, "@everyone 상세"), ROLE_ID);

        assertThat(data.getContent()).doesNotContain("<@&");
        assertThat(data.getMentionedRoles()).isEmpty();
        assertThat(data.getAllowedMentions()).isEmpty();
    }

    @Test
    void 역할_설정이_없으면_실패_알림도_멘션하지_않는다() {
        MessageCreateData data = OpsMessageFormatter.format(new OpsEvent(OpsEvent.Type.SEND_FAILED, "1팀", CYCLE, "원인"), null);

        assertThat(data.getContent()).doesNotContain("<@&");
        assertThat(data.getMentionedRoles()).isEmpty();
    }

    @Test
    void 이천자를_넘는_상세_내용은_자른다() {
        OpsEvent event = new OpsEvent(OpsEvent.Type.COLLECT_FAILED, "1팀", CYCLE, "가".repeat(5000));

        String text = OpsMessageFormatter.text(event, ROLE_ID);

        assertThat(text).hasSizeLessThanOrEqualTo(OpsMessageFormatter.MAX_LENGTH).endsWith("…")
                .startsWith("🟡 **정기 준비 중 레포 수집 실패**\n팀: 1팀 · 회차: 11-27 09:00\n");
        assertThat(OpsMessageFormatter.format(event, ROLE_ID).getContent()).hasSizeLessThanOrEqualTo(2000);
    }

    @Test
    void 팀과_회차가_없는_알림은_그_줄을_뺀다() {
        assertThat(OpsMessageFormatter.text(OpsEvent.of(OpsEvent.Type.STARTED, "MOA가 시작됐어요"), ROLE_ID))
                .isEqualTo("🟢 **MOA 시작**\nMOA가 시작됐어요");
    }
}
