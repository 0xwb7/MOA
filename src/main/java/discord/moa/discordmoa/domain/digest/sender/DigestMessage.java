package discord.moa.discordmoa.domain.digest.sender;

import java.util.List;

public record DigestMessage(Long mentionUserId, List<DigestBlock> blocks) {
}
