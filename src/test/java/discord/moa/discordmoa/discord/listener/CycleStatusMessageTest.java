package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.CycleStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CycleStatusMessageTest {

    @Test
    void 다음_회차와_직전_회차_결과를_보여준다() {
        CycleStatus status = new CycleStatus("1팀",
                Instant.parse("2026-11-27T12:00:00Z"), Instant.parse("2026-11-27T11:58:00Z"), DigestStatus.PREPARING,
                new CycleStatus.Result(Instant.parse("2026-11-27T00:00:00Z"), DigestStatus.FAILED, DigestType.FALLBACK,
                        null, "봇에게 #1팀 채널의 Send Messages 권한이 없어요.", "준비 마감까지 요약이 끝나지 않아 변경 목록으로 보내요",
                        0, 0),
                null, true);

        assertThat(CycleStatusMessage.format(status))
                .contains("**다음 정기 회차** 11/27 21:00 (준비 마감 11/27 20:58)")
                .contains("상태: PREPARING (준비 중)")
                .contains("회차: 11/27 09:00")
                .contains("결과: FAILED · 유형 FALLBACK (변경 목록)")
                .contains("실패 원인: 봇에게 #1팀 채널의 Send Messages 권한이 없어요.")
                .contains("요약 대신 변경 목록을 쓴 이유: 준비 마감까지")
                .doesNotContain("/발송테스트", "PM 확인");   // 전달된 항목이 없으면 확인 현황을 보여주지 않는다
    }

    @Test
    void 직전_회차의_PM_확인_현황을_보여준다() {
        CycleStatus status = new CycleStatus("1팀", Instant.parse("2026-11-27T12:00:00Z"),
                Instant.parse("2026-11-27T11:58:00Z"), null,
                new CycleStatus.Result(Instant.parse("2026-11-27T00:00:00Z"), DigestStatus.SENT, DigestType.LLM,
                        Instant.parse("2026-11-27T00:00:01Z"), null, null, 3, 2),
                null, true);

        assertThat(CycleStatusMessage.format(status)).contains("- PM 확인 2/3");
    }

    @Test
    void 기록이_없으면_없음으로_보여준다() {
        CycleStatus status = new CycleStatus("1팀", Instant.parse("2026-11-27T12:00:00Z"),
                Instant.parse("2026-11-27T11:58:00Z"), null, null, null, true);

        assertThat(CycleStatusMessage.format(status)).contains("상태: 없음 (아직 준비 전)", "- 기록 없음")
                .doesNotContain("활성 레포가 없어");
    }

    @Test
    void 활성_레포가_없으면_정기_회차_대상이_아니라고_알려준다() {
        CycleStatus status = new CycleStatus("1팀", Instant.parse("2026-11-27T12:00:00Z"),
                Instant.parse("2026-11-27T11:58:00Z"), null, null, null, false);

        assertThat(CycleStatusMessage.format(status)).contains("⚠️ 활성 레포가 없어 정기 회차 대상이 아니에요");
    }
}
