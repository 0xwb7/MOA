package discord.moa.discordmoa.discord.sender;

/**
 * 질문을 팀 채널에 올리지 못함. 메시지는 운영 알림에 그대로 쓴다.
 */
public class QuestionPostException extends RuntimeException {

    public QuestionPostException(String message) {
        super(message);
    }

    public QuestionPostException(String message, Throwable cause) {
        super(message, cause);
    }
}
