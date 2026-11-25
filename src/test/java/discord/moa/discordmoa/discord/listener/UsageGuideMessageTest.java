package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.guide.service.UsageGuide;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class UsageGuideMessageTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-25T05:00:00Z");            // 14:00 KST
    private static final Instant TONIGHT = Instant.parse("2026-11-25T12:00:00Z");        // 오늘 21:00
    private static final Instant TOMORROW = Instant.parse("2026-11-26T00:00:00Z");       // 내일 09:00
    private static final String URL = "https://app.notion.com/p/MOA-guide";

    @Test
    void 팀_PM에게는_상태_뜻과_켜진_버튼_안내와_다음_발송을_보여준다() {
        String text = UsageGuideMessage.format(new UsageGuide("1팀", true, null, 2, TONIGHT, NOW, SEOUL, true, true, URL));

        assertThat(text).isEqualTo("""
                MOA는 매일 9시·21시에 팀 레포 변경을 PM이 읽기 쉽게 요약해서 보내줘요.

                📋 '1팀' PM 안내
                🟢 기능 완성: 화면과 서버가 연결돼 기능이 완성됐어요
                🟡 일부만 됐어요: 서버나 화면 중 한쪽만 됐어요
                ⚪ 내부 작업: 화면에 드러나지 않는 정리·설정 작업이에요
                • 확인했어요: 읽은 항목에 누르면 팀이 확인 여부를 알 수 있어요
                • 개발자에게 질문하기: 항목에 대해 질문하면 그 변경을 만든 개발자에게 알림이 가요

                ⏰ 다음 발송: 오늘 21:00

                📖 자세한 사용법: <https://app.notion.com/p/MOA-guide>""");
        assertThat(text).doesNotContain("내 설정 상태", "개발자 사용법");
    }

    @Test
    void PM_안내에서_꺼진_버튼은_빼고_둘_다_꺼지면_버튼_줄이_없다() {
        assertThat(UsageGuideMessage.format(new UsageGuide("1팀", true, null, 2, TONIGHT, NOW, SEOUL, false, true, URL)))
                .doesNotContain("확인했어요").contains("개발자에게 질문하기");
        assertThat(UsageGuideMessage.format(new UsageGuide("1팀", true, null, 2, TONIGHT, NOW, SEOUL, false, false, URL)))
                .doesNotContain("•");
    }

    @Test
    void 연동하고_레포가_있는_개발자에게는_모두_체크와_개발자_사용법을_보여준다() {
        String text = UsageGuideMessage.format(new UsageGuide("1팀", false, "alice", 2, TOMORROW, NOW, SEOUL, true, true, URL));

        assertThat(text).isEqualTo("""
                MOA는 매일 9시·21시에 팀 레포 변경을 PM이 읽기 쉽게 요약해서 보내줘요.

                🛠 내 설정 상태
                ✅ 팀 채팅방: '1팀'
                ✅ GitHub 연동: alice
                ✅ 팀에 등록된 레포: 2개

                💡 개발자 사용법
                • `/연동 github:<아이디>`: 내 GitHub 계정을 Discord 계정과 연결해요. PM의 질문 알림을 받으려면 필요해요
                • `/레포등록 url:<레포 주소>`: 팀 채팅방에서 레포를 등록하면 그때부터 변경을 모아요
                • `/레포목록`: 팀에 등록된 레포를 확인해요. 등록 해제는 운영진에게 요청해 주세요
                • 요약은 커밋 메시지와 변경 내용으로 만들어요. 무엇을 했는지 드러나게 커밋 메시지를 써 주세요

                ⏰ 다음 발송: 내일 09:00

                📖 자세한 사용법: <https://app.notion.com/p/MOA-guide>""");
    }

    @Test
    void 연동이_안_되고_레포가_없으면_해야_할_명령어를_알려준다() {
        String text = UsageGuideMessage.format(new UsageGuide("1팀", false, null, 0, TOMORROW, NOW, SEOUL, false, false, URL));

        assertThat(text).contains("""
                ✅ 팀 채팅방: '1팀'
                ❌ GitHub 연동 안 됨 → `/연동 github:<아이디>`
                ❌ 팀에 등록된 레포 없음 → `/레포등록 url:<레포 주소>`
                """)
                .doesNotContain("질문 알림");   // 질문 버튼이 꺼져 있으면 그 설명은 뺀다
    }

    @Test
    void 팀_채널이_아니면_팀_줄_대신_안내하고_다음_발송은_보여주지_않는다() {
        String text = UsageGuideMessage.format(new UsageGuide(null, false, "alice", 0, null, NOW, SEOUL, true, true, URL));

        assertThat(text).contains("""
                🛠 내 설정 상태
                ✅ GitHub 연동: alice
                팀 채팅방에서 실행하면 팀 설정 상태도 보여줘요
                """)
                .contains("💡 개발자 사용법")
                .doesNotContain("✅ 팀 채팅방", "팀에 등록된 레포:", "팀에 등록된 레포 없음", "다음 발송", "PM 안내");
    }

    @Test
    void 가이드_URL이_없으면_그_줄을_뺀다() {
        String text = UsageGuideMessage.format(new UsageGuide("1팀", false, "alice", 1, TOMORROW, NOW, SEOUL, true, true, null));

        assertThat(text).doesNotContain("📖").endsWith("⏰ 다음 발송: 내일 09:00");
    }

    @Test
    void 다음_발송이_모레_이후면_날짜로_보여준다() {
        assertThat(UsageGuideMessage.nextSend(new UsageGuide("1팀", false, null, 0,
                Instant.parse("2026-11-27T00:00:00Z"), NOW, SEOUL, false, false, null))).isEqualTo("11/27 09:00");
    }

    @Test
    void 팀_이름과_GitHub_아이디의_마크다운은_이스케이프하고_2000자를_넘지_않는다() {
        String text = UsageGuideMessage.format(new UsageGuide("**팀**", false, "**alice**", 1, TOMORROW, NOW, SEOUL, true, true, URL));
        assertThat(text).contains("✅ 팀 채팅방: '\\*\\*팀\\*\\*'", "✅ GitHub 연동: \\*\\*alice\\*\\*");

        String longName = "가".repeat(3000);
        assertThat(UsageGuideMessage.format(new UsageGuide(longName, true, null, 1, TOMORROW, NOW, SEOUL, true, true, URL)))
                .hasSizeLessThanOrEqualTo(UsageGuideMessage.MAX_LENGTH);
    }
}
