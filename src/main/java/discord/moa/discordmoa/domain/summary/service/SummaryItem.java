package discord.moa.discordmoa.domain.summary.service;

import java.util.List;

public record SummaryItem(
        SummaryStatus status,
        String title,
        List<String> commitShas
) {
}
