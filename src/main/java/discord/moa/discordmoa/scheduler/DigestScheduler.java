package discord.moa.discordmoa.scheduler;

import discord.moa.discordmoa.domain.digest.service.CycleCalendar;
import discord.moa.discordmoa.domain.digest.service.DigestPreparer;
import discord.moa.discordmoa.domain.digest.service.DigestRecovery;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.global.config.SchedulingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;

/**
 * 정기 발송 트리거. 스케줄러 스레드에서는 실행기에 넘기기만 하고 바로 돌아온다.
 */
@Slf4j
@Component
public class DigestScheduler {

    // 스케줄러가 몇 분 늦게 실행돼도 같은 회차로 계산한다
    static final Duration SEND_LOOKBACK = Duration.ofMinutes(10);

    private final DigestPreparer digestPreparer;
    private final DigestService digestService;
    private final DigestRecovery digestRecovery;
    private final CycleCalendar calendar;
    private final ExecutorService prepareCoordinator;
    private final ExecutorService sendExecutor;
    private final Clock clock;

    public DigestScheduler(DigestPreparer digestPreparer, DigestService digestService, DigestRecovery digestRecovery,
                           CycleCalendar calendar,
                           @Qualifier(SchedulingConfig.PREPARE_COORDINATOR) ExecutorService prepareCoordinator,
                           @Qualifier(SchedulingConfig.SEND_EXECUTOR) ExecutorService sendExecutor,
                           Clock clock) {
        this.digestPreparer = digestPreparer;
        this.digestService = digestService;
        this.digestRecovery = digestRecovery;
        this.calendar = calendar;
        this.prepareCoordinator = prepareCoordinator;
        this.sendExecutor = sendExecutor;
        this.clock = clock;
    }

    @Scheduled(cron = "${schedule.prepare-cron}", zone = "${schedule.zone}")
    public void prepare() {
        Instant cycle = calendar.nextSendAfter(clock.instant());
        log.info("준비 트리거: 회차={}", cycle);
        prepareCoordinator.execute(() -> digestPreparer.prepareCycle(cycle));
    }

    @Scheduled(cron = "${schedule.send-cron}", zone = "${schedule.zone}")
    public void send() {
        calendar.latestSendAtOrBefore(clock.instant(), SEND_LOOKBACK).ifPresentOrElse(
                digestService::sendCycle,
                () -> log.error("발송 트리거가 실행됐지만 회차를 계산하지 못했어요: now={}", clock.instant()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        sendExecutor.execute(() -> {
            try {
                digestRecovery.recover();
            } catch (RuntimeException e) {
                log.error("재시작 복구 중 오류", e);
            }
        });
    }
}
