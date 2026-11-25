package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.DigestSendResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SendTestReplyTest {

    @Test
    void 변경이_없을_때_수집하지_못한_레포가_있으면_경고_안내를_보냈다고_알려준다() {
        assertThat(OpsCommandHandler.describeSend(result(DigestType.NO_CHANGE, List.of("team1/backend"))))
                .contains("일부 레포를 확인하지 못해")
                .doesNotContain("'변경 사항이 없어요' 안내");
        assertThat(OpsCommandHandler.describeSend(result(DigestType.NO_CHANGE, List.of())))
                .isEqualTo("✅ 변경이 없어 '변경 사항이 없어요' 안내를 보냈어요.");
    }

    @Test
    void 변경이_있을_때_수집하지_못한_레포가_있으면_메시지_끝_안내를_알려준다() {
        assertThat(OpsCommandHandler.describeSend(result(DigestType.LLM, List.of("team1/backend"))))
                .startsWith("✅ 발송 완료 (LLM 요약): 항목 2개, 메시지 1개")
                .endsWith("\n⚠️ 확인하지 못한 레포가 있다는 안내를 메시지 끝에 붙였어요.");
        assertThat(OpsCommandHandler.describeSend(result(DigestType.LLM, List.of())))
                .doesNotContain("확인하지 못한");
    }

    private static DigestSendResult result(DigestType type, List<String> uncollected) {
        return new DigestSendResult("1팀", type, true, type == DigestType.NO_CHANGE ? 0 : 2, 1, null, null, uncollected);
    }
}
