package discord.moa.discordmoa.domain.digest.sender;

import java.util.List;

public interface DigestSender {

    List<Long> send(Long channelId, List<DigestMessage> messages);
}
