package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.global.config.ScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

public class CycleCalendarTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CycleCalendar calendar = new CycleCalendar(new ScheduleProperties(SEOUL,
            "0 30 8,20 * * *", "0 0 9,21 * * *", Duration.ofMinutes(2), 3, Duration.ofSeconds(30), Duration.ofMinutes(30)));

    @Test
    void 준비_시각의_회차는_다음_발송_시각이다() {
        assertThat(calendar.nextSendAfter(kst(2026, 11, 27, 8, 30, 0))).isEqualTo(kst(2026, 11, 27, 9, 0, 0));
        assertThat(calendar.nextSendAfter(kst(2026, 11, 27, 20, 30, 0))).isEqualTo(kst(2026, 11, 27, 21, 0, 0));
        assertThat(calendar.nextSendAfter(kst(2026, 11, 27, 21, 0, 0))).isEqualTo(kst(2026, 11, 28, 9, 0, 0));
    }

    @Test
    void 발송_시각의_회차는_지금_이하의_가장_최근_발송_시각이다() {
        Duration lookback = Duration.ofMinutes(10);
        assertThat(calendar.latestSendAtOrBefore(kst(2026, 11, 27, 9, 0, 0), lookback)).contains(kst(2026, 11, 27, 9, 0, 0));
        // 스케줄러가 몇 초 늦게 실행돼도 같은 회차
        assertThat(calendar.latestSendAtOrBefore(kst(2026, 11, 27, 9, 0, 7), lookback)).contains(kst(2026, 11, 27, 9, 0, 0));
        assertThat(calendar.latestSendAtOrBefore(kst(2026, 11, 27, 9, 20, 0), lookback)).isEmpty();
        assertThat(calendar.latestSendAtOrBefore(kst(2026, 11, 27, 9, 20, 0), Duration.ofMinutes(30)))
                .contains(kst(2026, 11, 27, 9, 0, 0));
    }

    @Test
    void 준비_마감은_발송_2분_전이다() {
        assertThat(calendar.prepareDeadline(kst(2026, 11, 27, 9, 0, 0))).isEqualTo(kst(2026, 11, 27, 8, 58, 0));
    }

    @Test
    void 수동_테스트용_분_단위_cron도_계산한다() {
        CycleCalendar test = new CycleCalendar(new ScheduleProperties(SEOUL,
                "0 31 14 * * *", "0 33 14 * * *", Duration.ofMinutes(1), 3, Duration.ofSeconds(30), Duration.ofMinutes(30)));

        Instant prepareAt = kst(2026, 11, 27, 14, 31, 0);
        assertThat(test.nextSendAfter(prepareAt)).isEqualTo(kst(2026, 11, 27, 14, 33, 0));
        assertThat(test.prepareDeadline(kst(2026, 11, 27, 14, 33, 0))).isEqualTo(kst(2026, 11, 27, 14, 32, 0));
    }

    public static Instant kst(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, SEOUL).toInstant();
    }
}
