package discord.moa.discordmoa.domain.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class LoggingOpsNotifier implements OpsNotifier {

    private static final DateTimeFormatter CYCLE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    @Override
    public void notify(OpsEvent event) {
        String cycle = event.scheduledFor() == null ? "-" : CYCLE.format(event.scheduledFor());
        switch (event.type()) {
            case PREPARE_FAILED, SEND_FAILED, RECOVERY_ABANDONED ->
                    log.error("[운영 알림] {}: team={}, 회차={}, {}", event.type().label(), event.teamName(), cycle, event.detail());
            default ->
                    log.warn("[운영 알림] {}: team={}, 회차={}, {}", event.type().label(), event.teamName(), cycle, event.detail());
        }
    }
}
