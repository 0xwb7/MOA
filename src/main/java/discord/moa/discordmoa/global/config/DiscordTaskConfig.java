package discord.moa.discordmoa.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class DiscordTaskConfig {

    public static final String DISCORD_TASK_EXECUTOR = "discordTaskExecutor";

    private static final long SHUTDOWN_WAIT_SECONDS = 30;

    /**
     * GitHub·LLM 호출처럼 오래 걸릴 수 있는 슬래시 명령 처리를 JDA 이벤트 스레드 밖에서 실행한다.
     * 종료 시 정리는 아래 shutdown 빈이 맡는다 (기본 close()는 작업이 끝날 때까지 무한정 기다리므로 쓰지 않는다).
     */
    @Bean(name = DISCORD_TASK_EXECUTOR, destroyMethod = "")
    public ExecutorService discordTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public DisposableBean discordTaskExecutorShutdown(ExecutorService discordTaskExecutor) {
        return () -> {
            discordTaskExecutor.shutdown();
            if (!discordTaskExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("종료 대기 {}초가 지나 남은 명령 처리를 중단해요.", SHUTDOWN_WAIT_SECONDS);
                discordTaskExecutor.shutdownNow();
            }
        };
    }
}
