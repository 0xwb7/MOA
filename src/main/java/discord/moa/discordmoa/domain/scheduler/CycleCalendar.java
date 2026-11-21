package discord.moa.discordmoa.domain.scheduler;

import discord.moa.discordmoa.global.config.ScheduleProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * cron 설정으로 회차 시각을 계산한다. 회차 = send-cron의 발송 시각.
 */
@Component
public class CycleCalendar {

    private final CronExpression prepareCron;
    private final CronExpression sendCron;
    private final ZoneId zone;
    private final Duration prepareDeadlineBefore;

    public CycleCalendar(ScheduleProperties properties) {
        this.prepareCron = CronExpression.parse(properties.prepareCron());
        this.sendCron = CronExpression.parse(properties.sendCron());
        this.zone = properties.zone();
        this.prepareDeadlineBefore = properties.prepareDeadlineBefore();
    }

    /**
     * 준비 단계가 맡을 회차: 지금 이후의 첫 발송 시각.
     */
    public Instant nextSendAfter(Instant now) {
        return next(sendCron, now);
    }

    public Instant nextPrepareAfter(Instant now) {
        return next(prepareCron, now);
    }

    /**
     * 발송 단계가 맡을 회차: lookback 안에서 지금 이하인 가장 최근 발송 시각.
     * 스케줄러가 몇 초 늦게 실행돼도 같은 회차로 계산된다.
     */
    public Optional<Instant> latestSendAtOrBefore(Instant now, Duration lookback) {
        Instant candidate = next(sendCron, now.minus(lookback));
        if (candidate.isAfter(now)) {
            return Optional.empty();
        }
        Instant after = next(sendCron, candidate);
        while (!after.isAfter(now)) {
            candidate = after;
            after = next(sendCron, candidate);
        }
        return Optional.of(candidate);
    }

    public Instant prepareDeadline(Instant cycle) {
        return cycle.minus(prepareDeadlineBefore);
    }

    public ZoneId zone() {
        return zone;
    }

    private Instant next(CronExpression cron, Instant after) {
        ZonedDateTime next = cron.next(after.atZone(zone));
        if (next == null) {
            throw new IllegalStateException("cron으로 다음 시각을 계산할 수 없어요: " + cron);
        }
        return next.toInstant();
    }
}
