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
                        null, "봇에게 #1팀 채널의 Send Messages 권한이 없어요.", "준비 마감까지 요약이 끝나지 않아 변경 목록으로 보내요"),
                null);

        assertThat(CycleStatusMessage.format(status))
                .contains("**다음 정기 회차** 11/27 21:00 (준비 마감 11/27 20:58)")
                .contains("상태: PREPARING (준비 중)")
                .contains("회차: 11/27 09:00")
                .contains("결과: FAILED · 유형 FALLBACK (변경 목록)")
                .contains("실패 원인: 봇에게 #1팀 채널의 Send Messages 권한이 없어요.")
                .contains("요약 대신 변경 목록을 쓴 이유: 준비 마감까지")
                .doesNotContain("/발송테스트");
    }

    @Test
    void 기록이_없으면_없음으로_보여준다() {
        CycleStatus status = new CycleStatus("1팀", Instant.parse("2026-11-27T12:00:00Z"),
                Instant.parse("2026-11-27T11:58:00Z"), null, null, null);

        assertThat(CycleStatusMessage.format(status)).contains("상태: 없음 (아직 준비 전)", "- 기록 없음");
    }
}
