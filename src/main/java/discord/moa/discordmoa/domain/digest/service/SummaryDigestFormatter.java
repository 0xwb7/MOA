package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.springframework.stereotype.Component;

@Component
public class SummaryDigestFormatter {

    public String header(String teamName, Long pmDiscordUserId) {
        return "<@" + pmDiscordUserId + "> 지난 12시간 동안 '" + MarkdownSanitizer.escape(teamName) + "'에 이런 변경이 있었어요\n"
                + "-# 🟢 바로 써 볼 수 있어요 · 🟡 일부만 됐어요 · ⚪ 내부 작업";
    }

    public String itemText(SummaryItem item) {
        return item.status().emoji() + " " + MarkdownSanitizer.escape(item.title());
    }
}
