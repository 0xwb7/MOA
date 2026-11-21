package discord.moa.discordmoa.domain.team.service;

/**
 * 같은 팀에 대해 다른 작업(준비, 발송, /발송테스트, /수집테스트)이 진행 중이다.
 * IllegalStateException이라 기존 명령 핸들러가 메시지를 그대로 보여준다.
 */
public class TeamBusyException extends IllegalStateException {

    public TeamBusyException(String teamName) {
        super("'" + teamName + "' 팀은 지금 처리 중이에요. 잠시 후 다시 시도해 주세요.");
    }
}
