package discord.moa.discordmoa.domain.ops;

/**
 * 운영 알림. 지금은 로그만 남기고, 다음 작업에서 운영진 장애 알림 채널로 보내는 구현체를 붙인다.
 * 구현체는 예외를 던지지 않아야 한다 (알림 실패가 준비·발송을 막으면 안 된다).
 */
public interface OpsNotifier {

    void notify(OpsEvent event);
}
