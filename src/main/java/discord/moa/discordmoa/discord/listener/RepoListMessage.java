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
                    .append(" · 마지막 수집 ").append(time(repo.getLastCollectedAt(), "아직 없음"))
                    .append(" · 커서 ").append(cursor(repo.getLastProcessedSha())).append('\n');
        }
        return text.toString();
    }

    private static String time(Instant instant, String missing) {
        return instant == null ? missing : TIME.format(instant);
    }

    private static String cursor(String sha) {
        if (sha == null) {
            return "없음 (첫 커밋부터 수집)";
        }
        return "`" + sha.substring(0, Math.min(SHORT_SHA, sha.length())) + "`";
    }
}
