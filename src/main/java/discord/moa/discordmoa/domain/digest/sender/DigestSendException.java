package discord.moa.discordmoa.domain.digest.sender;

import lombok.Getter;

import java.util.List;

@Getter
public class DigestSendException extends RuntimeException {

    private final List<Long> sentMessageIds;

    public DigestSendException(String message, List<Long> sentMessageIds) {
        super(message);
        this.sentMessageIds = List.copyOf(sentMessageIds);
    }

    public DigestSendException(String message, List<Long> sentMessageIds, Throwable cause) {
        super(message, cause);
        this.sentMessageIds = List.copyOf(sentMessageIds);
    }
}
