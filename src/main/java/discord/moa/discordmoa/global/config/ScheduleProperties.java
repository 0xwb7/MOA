package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("schedule")
public record ScheduleProperties(
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("0 30 8,20 * * *") String prepareCron,   // 수집 + 요약 + READY 저장
        @DefaultValue("0 0 9,21 * * *") String sendCron,       // 저장된 것을 발송 (회차 = 이 시각)
        @DefaultValue("2m") Duration prepareDeadlineBefore,    // 발송 몇 분 전까지 준비를 끝낼지
        @DefaultValue("3") int prepareConcurrency,             // 동시에 준비할 최대 팀 수 (OpenAI 요청 한도)
        @DefaultValue("30s") Duration sendLockWait,            // 발송 단계가 팀 락을 기다리는 최대 시간
        @DefaultValue("30m") Duration recoveryWindow            // 재시작 후 이 시간 안이면 늦게라도 발송
) {
}
