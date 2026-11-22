package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.CycleStatus;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * /회차상태 응답(에페메럴) 텍스트.
 */
final class CycleStatusMessage {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter TIME_SEC = DateTimeFormatter.ofPattern("M/d HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private CycleStatusMessage() {
    }

    static String format(CycleStatus status) {
        StringBuilder text = new StringBuilder("🗓️ '").append(MarkdownSanitizer.escape(status.teamName())).append("' 회차 상태\n\n");

        text.append("**다음 정기 회차** ").append(time(status.nextCycle()))
                .append(" (준비 마감 ").append(time(status.nextPrepareDeadline())).append(")\n")
                .append("- 상태: ").append(nextStatus(status.nextStatus())).append("\n\n");

        text.append("**직전 정기 회차**\n").append(result(status.lastScheduled(), TIME));
        if (status.lastManual() != null) {
            text.append("\n**마지막 /발송테스트**\n").append(result(status.lastManual(), TIME_SEC));
        }
        return text.toString();
    }

    private static String nextStatus(DigestStatus status) {
        if (status == null) {
            return "없음 (아직 준비 전)";
        }
        return switch (status) {
            case PREPARING -> "PREPARING (준비 중)";
            case READY -> "READY (발송 대기)";
            case SENT -> "SENT";
            case FAILED -> "FAILED";
        };
    }

    private static String result(CycleStatus.Result result, DateTimeFormatter format) {
        if (result == null) {
            return "- 기록 없음\n";
        }
        StringBuilder text = new StringBuilder("- 회차: ").append(format.format(result.scheduledFor())).append('\n')
                .append("- 결과: ").append(result.status());
        if (result.type() != null) {
            text.append(" · 유형 ").append(type(result.type()));
        }
        if (result.sentAt() != null) {
            text.append(" · 발송 ").append(TIME_SEC.format(result.sentAt()));
        }
        text.append('\n');
        if (result.deliveredItems() > 0) {
            text.append("- PM 확인 ").append(result.confirmedItems()).append('/').append(result.deliveredItems()).append('\n');
        }
        if (result.failureReason() != null) {
            text.append("- 실패 원인: ").append(MarkdownSanitizer.escape(result.failureReason())).append('\n');
        }
        if (result.fallbackReason() != null) {
            text.append("- 요약 대신 변경 목록을 쓴 이유: ").append(MarkdownSanitizer.escape(result.fallbackReason())).append('\n');
        }
        return text.toString();
    }

    private static String type(DigestType type) {
        return switch (type) {
            case LLM -> "LLM 요약";
            case FALLBACK -> "FALLBACK (변경 목록)";
            case NO_CHANGE -> "NO_CHANGE (변경 없음)";
        };
    }

    private static String time(Instant instant) {
        return TIME.format(instant);
    }
}
