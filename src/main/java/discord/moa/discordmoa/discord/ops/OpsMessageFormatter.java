package discord.moa.discordmoa.discord.ops;

import discord.moa.discordmoa.domain.ops.OpsEvent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;

/**
 * 운영 알림 메시지. 🔴 실패만 운영진 역할을 멘션하고, 그 밖의 멘션(@everyone 등)은 모두 막는다.
 */
final class OpsMessageFormatter {

    static final int MAX_LENGTH = 2000;   // Discord 메시지 본문 한도

    private static final DateTimeFormatter CYCLE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private OpsMessageFormatter() {
    }

    static MessageCreateData format(OpsEvent event, Long roleId) {
        boolean mentionRole = mentionsRole(event, roleId);
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setContent(text(event, roleId))
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        if (mentionRole) {
            builder.mentionRoles(List.of(String.valueOf(roleId)));
        }
        return builder.build();
    }

    static String text(OpsEvent event, Long roleId) {
        StringBuilder head = new StringBuilder()
                .append(event.type().severity().emoji()).append(" **").append(event.type().label()).append("**");
        if (mentionsRole(event, roleId)) {
            head.append(" <@&").append(roleId).append('>');
        }
        String where = where(event);
        if (!where.isEmpty()) {
            head.append('\n').append(where);
        }
        if (event.detail() == null || event.detail().isBlank()) {
            return head.toString();
        }
        head.append('\n');
        return head + cut(MarkdownSanitizer.escape(event.detail()), MAX_LENGTH - head.length());
    }

    private static boolean mentionsRole(OpsEvent event, Long roleId) {
        return roleId != null && event.type().severity() == OpsEvent.Severity.FAILURE;
    }

    private static String where(OpsEvent event) {
        StringBuilder text = new StringBuilder();
        if (event.teamName() != null) {
            text.append("팀: ").append(MarkdownSanitizer.escape(event.teamName()));
        }
        if (event.scheduledFor() != null) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append("회차: ").append(CYCLE.format(event.scheduledFor()));
        }
        return text.toString();
    }

    private static String cut(String detail, int available) {
        if (detail.length() <= available) {
            return detail;
        }
        int end = Math.max(0, available - 1);
        if (end > 0 && Character.isHighSurrogate(detail.charAt(end - 1))) {
            end--;
        }
        return detail.substring(0, end) + "…";
    }
}
