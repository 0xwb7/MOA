package discord.moa.discordmoa.domain.scheduler;

import discord.moa.discordmoa.domain.digest.service.DigestPreparer;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.global.config.ScheduleProperties;
import discord.moa.discordmoa.global.config.SchedulingConfig;
import discord.moa.discordmoa.support.DirectExecutorService;
import discord.moa.discordmoa.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static discord.moa.discordmoa.domain.scheduler.CycleCalendarTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DigestSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DigestPreparer preparer = mock(DigestPreparer.class);
    private final DigestService service = mock(DigestService.class);
    private final DigestRecovery recovery = mock(DigestRecovery.class);
    private final MutableClock clock = new MutableClock(kst(2026, 11, 27, 8, 30, 0), SEOUL);
    private final CycleCalendar calendar = new CycleCalendar(new ScheduleProperties(SEOUL,
            "0 30 8,20 * * *", "0 0 9,21 * * *", Duration.ofMinutes(2), 3, Duration.ofSeconds(30), Duration.ofMinutes(30)));

    @Test
    void 준비_트리거는_다음_발송_회차를_코디네이터에_넘긴다() {
        scheduler(new DirectExecutorService()).prepare();

        verify(preparer).prepareCycle(kst(2026, 11, 27, 9, 0, 0));
    }

    @Test
    void 준비_트리거는_스케줄러_스레드를_막지_않는다() {
        ExecutorService coordinator = mock(ExecutorService.class);

        scheduler(coordinator).prepare();

        verify(coordinator).execute(any());
        verify(preparer, never()).prepareCycle(any());   // 실행기에 넘기기만 하고 바로 돌아온다
    }

    @Test
    void 발송_트리거는_몇_초_늦게_실행돼도_같은_회차를_보낸다() {
        clock.set(kst(2026, 11, 27, 9, 0, 3));

        scheduler(new DirectExecutorService()).send();

        verify(service).sendCycle(kst(2026, 11, 27, 9, 0, 0));
    }

    @Test
    void 실제_설정으로_cron_두_개가_서울_시간대로_등록된다() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SchedulingConfig.class)
                .withBean(CycleCalendar.class)
                .withBean(DigestPreparer.class, () -> preparer)
                .withBean(DigestService.class, () -> service)
                .withBean(DigestRecovery.class, () -> recovery)
                .withBean(DigestScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    List<String> crons = context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                            .map(task -> task.getTask())
                            .filter(CronTask.class::isInstance)
                            .map(task -> ((CronTask) task).getExpression())
                            .toList();
                    assertThat(crons).containsExactlyInAnyOrder("0 30 8,20 * * *", "0 0 9,21 * * *");
                    assertThat(context.getBean(ScheduleProperties.class).zone()).isEqualTo(SEOUL);
                    assertThat(context.getBean(SchedulingConfig.PREPARE_EXECUTOR, ExecutorService.class)).isNotNull();
                });
    }

    private DigestScheduler scheduler(ExecutorService coordinator) {
        return new DigestScheduler(preparer, service, recovery, calendar, coordinator, new DirectExecutorService(), clock);
    }
}
