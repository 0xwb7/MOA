package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * /레포목록 응답(에페메럴) 텍스트.
 */
final class RepoListMessage {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final int SHORT_SHA = 7;

    private RepoListMessage() {
    }

    static String format(TrackedRepoService.RepoList list) {
        String team = MarkdownSanitizer.escape(list.teamName());
        if (list.repos().isEmpty()) {
            return "📦 '" + team + "' 팀에 등록된 레포가 없어요. /레포등록 으로 레포를 등록해 주세요.";
        }
        StringBuilder text = new StringBuilder("📦 '").append(team).append("' 레포 목록 (")
                .append(list.repos().size()).append("개)\n");
        for (TrackedRepo repo : list.repos()) {
            text.append("• **").append(MarkdownSanitizer.escape(repo.getFullName())).append("**\n")
                    .append("  등록 ").append(time(repo.getRegisteredAt(), "기록 없음"))
                    .append(" · 마지막 수집 ").append(lastCollected(repo.getLastCollectedAt(), repo.getLastProcessedSha()))
                    .append('\n');
        }
        return text.toString();
    }

    private static String time(Instant instant, String missing) {
        return instant == null ? missing : TIME.format(instant);
    }

    /**
     * 수집한 적 있음: "11/23 20:30 (`abcdef1`까지)", 없음: "없음 (`abcdef1`부터 시작)".
     * 커서가 없으면 수집 전은 "없음 (첫 커밋부터 시작)", 수집 후(빈 레포)는 시각만.
     */
    private static String lastCollected(Instant collectedAt, String sha) {
        if (collectedAt == null) {
            return "없음 (" + (sha == null ? "첫 커밋" : shortSha(sha)) + "부터 시작)";
        }
        String time = TIME.format(collectedAt);
        return sha == null ? time : time + " (" + shortSha(sha) + "까지)";
    }

    private static String shortSha(String sha) {
        return "`" + sha.substring(0, Math.min(SHORT_SHA, sha.length())) + "`";
    }
}
