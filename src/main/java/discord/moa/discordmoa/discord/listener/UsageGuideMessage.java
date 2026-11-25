package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.guide.service.UsageGuide;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /사용법 응답(에페메럴, 2000자 이내) 텍스트.
 * 팀 채널에서 그 팀 PM이 실행하면 PM 안내, 그 밖에는 개발자 설정 체크리스트와 개발자 사용법을 보여준다.
 */
final class UsageGuideMessage {

    static final int MAX_LENGTH = 2000;
    static final String INTRO = "MOA는 매일 9시·21시에 팀 레포 변경을 PM이 읽기 쉽게 요약해서 보내줘요.";
    static final String NOT_TEAM_CHANNEL = "팀 채팅방에서 실행하면 팀 설정 상태도 보여줘요";

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M/d HH:mm");

    private UsageGuideMessage() {
    }

    static String format(UsageGuide guide) {
        StringBuilder text = new StringBuilder(INTRO).append("\n\n");
        if (guide.teamChannel() && guide.pm()) {
            appendPmGuide(text, guide);
        } else {
            appendDeveloperChecklist(text, guide);
            text.append('\n');
            appendDeveloperGuide(text, guide);
        }
        if (guide.teamChannel() && guide.nextSend() != null) {
            text.append("\n⏰ 다음 발송: ").append(nextSend(guide)).append('\n');
        }
        if (guide.guideUrl() != null) {
            text.append("\n📖 자세한 사용법: <").append(guide.guideUrl()).append(">\n");
        }
        String message = text.toString().stripTrailing();
        return message.length() <= MAX_LENGTH ? message : message.substring(0, MAX_LENGTH - 1) + "…";
    }

    private static void appendPmGuide(StringBuilder text, UsageGuide guide) {
        text.append("📋 '").append(escape(guide.teamName())).append("' PM 안내\n")
                .append("🟢 기능 완성: 화면과 서버가 연결돼 기능이 완성됐어요\n")
                .append("🟡 일부만 됐어요: 서버나 화면 중 한쪽만 됐어요\n")
                .append("⚪ 내부 작업: 화면에 드러나지 않는 정리·설정 작업이에요\n");
        if (guide.confirmButtonEnabled()) {
            text.append("• 확인했어요: 읽은 항목에 누르면 팀이 확인 여부를 알 수 있어요\n");
        }
        if (guide.askButtonEnabled()) {
            text.append("• 개발자에게 질문하기: 항목에 대해 질문하면 그 변경을 만든 개발자에게 알림이 가요\n");
        }
    }

    private static void appendDeveloperChecklist(StringBuilder text, UsageGuide guide) {
        text.append("🛠 내 설정 상태\n");
        if (guide.teamChannel()) {
            text.append("✅ 팀 채팅방: '").append(escape(guide.teamName())).append("'\n");
        }
        if (guide.githubLogin() != null) {
            text.append("✅ GitHub 연동: ").append(escape(guide.githubLogin())).append('\n');
        } else {
            text.append("❌ GitHub 연동 안 됨 → `/연동 github:<아이디>`\n");
        }
        if (!guide.teamChannel()) {
            text.append(NOT_TEAM_CHANNEL).append('\n');
        } else if (guide.activeRepoCount() > 0) {
            text.append("✅ 등록된 레포: ").append(guide.activeRepoCount()).append("개\n");
        } else {
            text.append("❌ 등록된 레포 없음 → `/레포등록 url:<레포 주소>`\n");
        }
    }

    private static void appendDeveloperGuide(StringBuilder text, UsageGuide guide) {
        text.append("💡 개발자 사용법\n")
                .append("• `/연동 github:<아이디>`: 내 GitHub 계정을 Discord 계정과 연결해요");
        if (guide.askButtonEnabled()) {
            text.append(". PM의 질문 알림을 받으려면 필요해요");
        }
        text.append('\n')
                .append("• `/레포등록 url:<레포 주소>`: 팀 채팅방에서 레포를 등록하면 그때부터 변경을 모아요\n")
                .append("• `/레포목록`: 팀에 등록된 레포를 확인해요. 등록 해제는 운영진에게 요청해 주세요\n")
                .append("• 요약은 커밋 메시지와 변경 내용으로 만들어요. 무엇을 했는지 드러나게 커밋 메시지를 써 주세요\n");
    }

    /**
     * 같은 날이면 "오늘 21:00", 다음 날이면 "내일 09:00", 그 뒤면 "11/27 09:00".
     */
    static String nextSend(UsageGuide guide) {
        ZonedDateTime next = guide.nextSend().atZone(guide.zone());
        LocalDate today = guide.now().atZone(guide.zone()).toLocalDate();
        if (next.toLocalDate().equals(today)) {
            return "오늘 " + HOUR_MINUTE.format(next);
        }
        if (next.toLocalDate().equals(today.plusDays(1))) {
            return "내일 " + HOUR_MINUTE.format(next);
        }
        return MONTH_DAY.format(next);
    }

    private static String escape(String value) {
        return MarkdownSanitizer.escape(value);
    }
}
