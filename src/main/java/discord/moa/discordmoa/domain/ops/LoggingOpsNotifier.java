package discord.moa.discordmoa.domain.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 운영 알림을 로그로 남긴다. Discord 전송 여부와 관계없이 항상 기록되도록 DiscordOpsNotifier가 먼저 호출한다.
 */
@Slf4j
@Component
public class LoggingOpsNotifier implements OpsNotifier {

    private static final DateTimeFormatter CYCLE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    @Override
    public void notify(OpsEvent event) {
        String cycle = event.scheduledFor() == null ? "-" : CYCLE.format(event.scheduledFor());
        String team = event.teamName() == null ? "-" : event.teamName();
        switch (event.type().severity()) {
            case FAILURE -> log.error("[운영 알림] {}: team={}, 회차={}, {}", event.type().label(), team, cycle, event.detail());
            case WARNING -> log.warn("[운영 알림] {}: team={}, 회차={}, {}", event.type().label(), team, cycle, event.detail());
            case INFO -> log.info("[운영 알림] {}: team={}, 회차={}, {}", event.type().label(), team, cycle, event.detail());
        }
    }
}
