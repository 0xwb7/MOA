package discord.moa.discordmoa.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 정기 발송 스케줄링.
 * <p>
 * 스프링 스케줄러 스레드는 cron 트리거만 맡고 오래 걸리는 일은 하지 않는다.
 * <ul>
 *     <li>준비: 코디네이터(1스레드)가 마감까지 기다리고, 팀 작업은 준비 실행기(고정 N스레드 = 동시 준비 팀 수)에서 돈다.</li>
 *     <li>발송: 팀마다 발송 실행기(가상 스레드)에서 병렬로 돈다. 한 팀이 락을 30초 기다려도 다른 팀이 밀리지 않는다.</li>
 * </ul>
 * 그래서 준비가 아무리 길어져도 발송 cron은 정각에 실행된다.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ScheduleProperties.class)
public class SchedulingConfig {

    public static final String PREPARE_COORDINATOR = "digestPrepareCoordinator";
    public static final String PREPARE_EXECUTOR = "digestPrepareExecutor";
    public static final String SEND_EXECUTOR = "digestSendExecutor";

    private static final long SEND_SHUTDOWN_WAIT_SECONDS = 30;

    @Bean
    public Clock clock(ScheduleProperties properties) {
        return Clock.system(properties.zone());
    }

    @Bean(name = PREPARE_COORDINATOR, destroyMethod = "")
    public ExecutorService digestPrepareCoordinator() {
        return Executors.newSingleThreadExecutor(named("digest-prepare-coordinator"));
    }

    @Bean(name = PREPARE_EXECUTOR, destroyMethod = "")
    public ExecutorService digestPrepareExecutor(ScheduleProperties properties) {
        return Executors.newFixedThreadPool(properties.prepareConcurrency(), named("digest-prepare"));
    }

    @Bean(name = SEND_EXECUTOR, destroyMethod = "")
    public ExecutorService digestSendExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 준비는 종료 시 바로 중단한다 (준비가 빠진 회차는 발송 시점 fallback이나 재시작 복구가 처리한다).
     * 발송은 진행 중인 것이 끝나도록 최대 30초 기다린다.
     */
    @Bean
    public DisposableBean digestExecutorsShutdown(@Qualifier(PREPARE_COORDINATOR) ExecutorService coordinator,
                                                  @Qualifier(PREPARE_EXECUTOR) ExecutorService prepare,
                                                  @Qualifier(SEND_EXECUTOR) ExecutorService send) {
        return () -> {
            coordinator.shutdownNow();
            prepare.shutdownNow();
            send.shutdown();
            if (!send.awaitTermination(SEND_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("종료 대기 {}초가 지나 남은 발송 작업을 중단해요.", SEND_SHUTDOWN_WAIT_SECONDS);
                send.shutdownNow();
            }
        };
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
