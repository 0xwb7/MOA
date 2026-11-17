package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FallbackDigestFormatter {

    static final int MAX_COMMITS_PER_ITEM = 10;
    static final int MAX_COMMIT_LINE_LENGTH = 100;

    private static final String GITHUB = "https://github.com/";

    public String header(String teamName, Long pmDiscordUserId) {
        return "<@" + pmDiscordUserId + "> 지난 12시간 동안 '" + escape(teamName) + "'에 변경이 있었어요\n"
                + "요약을 만들지 못해 변경 목록을 보내드려요";
    }

    public String noChange(String teamName) {
        return "지난 12시간 동안 '" + escape(teamName) + "'에 변경 사항이 없어요";
    }

    public String itemText(String repoFullName, List<ChangeBatch> batches) {
        List<CommitSnapshot> commits = batches.stream()
                .flatMap(batch -> batch.getCommits().stream())
                .toList();
        boolean forcePushDetected = batches.stream().anyMatch(ChangeBatch::isForcePushDetected);
        boolean truncated = batches.stream().anyMatch(ChangeBatch::isTruncated);

        StringBuilder text = new StringBuilder()
                .append("📦 ").append(escape(repoFullName)).append(" (커밋 ").append(commits.size()).append("개)\n");
        if (forcePushDetected) {
            text.append("⚠️ 히스토리가 변경되어 최근 커밋만 표시해요\n");
        }
        if (truncated) {
            text.append("⚠️ 일부 커밋만 표시해요\n");
        }

        commits.stream()
                .limit(MAX_COMMITS_PER_ITEM)
                .forEach(commit -> text.append("- ").append(firstLine(commit.message())).append('\n'));
        if (commits.size() > MAX_COMMITS_PER_ITEM) {
            text.append("- 외 ").append(commits.size() - MAX_COMMITS_PER_ITEM).append("개\n");
        }

        text.append("🔗 [변경 내역 보기](").append(changesUrl(repoFullName, batches, forcePushDetected)).append(')');
        return text.toString();
    }

    static String changesUrl(String repoFullName, List<ChangeBatch> batches, boolean forcePushDetected) {
        String base = batches.getFirst().getBaseSha();
        String head = batches.getLast().getHeadSha();
        if (base == null || forcePushDetected) {
            return GITHUB + repoFullName + "/commits/" + head;
        }
        return GITHUB + repoFullName + "/compare/" + base + "..." + head;
    }

    static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "(메시지 없음)";
        }
        String line = message.strip().lines().findFirst().orElse("").strip();
        if (line.length() > MAX_COMMIT_LINE_LENGTH) {
            int end = MAX_COMMIT_LINE_LENGTH;
            if (Character.isHighSurrogate(line.charAt(end - 1))) {
                end--;
            }
            line = line.substring(0, end) + "…";
        }
        return escape(line);
    }

    private static String escape(String text) {
        return MarkdownSanitizer.escape(text);
    }
}
