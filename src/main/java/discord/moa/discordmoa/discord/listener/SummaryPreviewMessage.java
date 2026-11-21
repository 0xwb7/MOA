package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryPreview;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.summary.service.SummaryStats;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /요약미리보기 응답(에페메럴, 2000자 이내) 텍스트를 만든다.
 */
final class SummaryPreviewMessage {

    static final int MAX_LENGTH = 2000;

    private SummaryPreviewMessage() {
    }

    static String format(SummaryPreview preview) {
        SummaryResult result = preview.result();
        String title = "🔎 '" + MarkdownSanitizer.escape(preview.teamName()) + "' 요약 미리보기 (발송·상태 변경 없음)\n\n";
        if (!result.hasChanges()) {
            return title + "아직 발송하지 않은 수집 변경이 없어요. /수집테스트 로 먼저 수집해 주세요.";
        }

        String footer = "\n" + stats(result.stats());
        List<String> body = new ArrayList<>();
        if (result.succeeded()) {
            result.items().forEach(item -> body.add(line(item)));
        } else {
            body.add("⚠️ 요약을 만들지 못했어요: " + result.fallbackReason());
            body.add("실제 발송이었다면 변경 목록(fallback)으로 보냈을 거예요.");
        }
        return fit(title, body, footer);
    }

    private static String line(SummaryItem item) {
        return item.status().emoji() + " " + MarkdownSanitizer.escape(item.title())
                + " (커밋 " + item.commitShas().size() + "개)";
    }

    private static String stats(SummaryStats stats) {
        return String.format(Locale.ROOT, "-# 입력 %,d자 · 토큰 입력 %,d / 출력 %,d · %.1f초 · 시도 %d회 · 프롬프트 %s · 모델 %s",
                stats.inputChars(), stats.inputTokens(), stats.outputTokens(), stats.durationMs() / 1000.0,
                stats.attempts(), stats.promptVersion(), stats.model());
    }

    private static String fit(String title, List<String> body, String footer) {
        StringBuilder text = new StringBuilder(title);
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i) + "\n";
            String rest = "… 외 " + (body.size() - i) + "개\n";
            boolean last = i == body.size() - 1;
            int needed = text.length() + line.length() + footer.length() + (last ? 0 : rest.length());
            if (needed > MAX_LENGTH) {
                text.append(rest);
                break;
            }
            text.append(line);
        }
        String result = text + footer;
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }
}
