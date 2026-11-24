package discord.moa.discordmoa.domain.question.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionMessageFormatterTest {

    private static final String SHA_A = "abc1234" + "0".repeat(33);
    private static final String SHA_B = "def5678" + "0".repeat(33);

    @Test
    void 질문자_항목_질문_개발자_관련_커밋_순서로_쓴다() {
        String text = QuestionMessageFormatter.format(7L, "로그인 기능이 완성됐어요", "🟢 로그인 기능이 완성됐어요",
                "로그인에 실패하면 어떤 화면이 나와요?",
                new QuestionTargets(List.of(1001L, 1002L), List.of("carol")),
                List.of(new QuestionCommit(SHA_A, "https://github.com/t/b/commit/" + SHA_A), new QuestionCommit(SHA_B, null)));

        assertThat(text).isEqualTo("""
                ❓ <@7>님의 질문
                > 항목: 로그인 기능이 완성됐어요
                로그인에 실패하면 어떤 화면이 나와요?
                → <@1001> <@1002> · carol (디스코드 미연동)
                관련 커밋: [abc1234](https://github.com/t/b/commit/%s) `def5678`""".formatted(SHA_A));
    }

    @Test
    void 멘션할_사람이_없으면_팀_개발자분들께_부탁한다() {
        assertThat(QuestionMessageFormatter.targetLine(new QuestionTargets(List.of(), List.of())))
                .isEqualTo("팀 개발자분들 확인 부탁드려요");
        assertThat(QuestionMessageFormatter.targetLine(new QuestionTargets(List.of(), List.of("my_bot", "Dave"))))
                .isEqualTo("팀 개발자분들 확인 부탁드려요 · my_bot (디스코드 미연동), Dave (디스코드 미연동)");
    }

    @Test
    void 커밋이_5개를_넘으면_5개만_링크하고_나머지는_개수로_쓴다() {
        List<QuestionCommit> commits = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> new QuestionCommit(i + "000000" + "0".repeat(33), "https://github.com/t/b/commit/" + i))
                .toList();

        String line = QuestionMessageFormatter.commitLine(commits);

        assertThat(line).startsWith("관련 커밋: [1000000](https://github.com/t/b/commit/1) ")
                .contains("[5000000](https://github.com/t/b/commit/5)")
                .doesNotContain("6000000")
                .endsWith(" 외 2개");
        assertThat(QuestionMessageFormatter.commitLine(commits.subList(0, 5))).doesNotContain("외");
        assertThat(QuestionMessageFormatter.commitLine(List.of())).isEmpty();
    }

    @Test
    void 질문의_마크다운은_이스케이프하고_everyone은_글자로만_남긴다() {
        String text = QuestionMessageFormatter.format(7L, "**로그인**", null, "@everyone **급해요** <@&999>",
                new QuestionTargets(List.of(1001L), List.of()), List.of());

        // 멘션이 실제로 동작하지 않게 하는 것은 게시할 때의 AllowedMentions다 (JdaQuestionPosterTest)
        assertThat(text).contains("> 항목: \\*\\*로그인\\*\\*\n")
                .contains("@everyone \\*\\*급해요\\*\\* <@&999>\n");
    }

    @Test
    void 제목이_없는_항목은_텍스트의_첫_줄을_쓰고_100자로_자른다() {
        assertThat(QuestionMessageFormatter.itemLabel(null, "📦 team1/backend (커밋 3개)\n- 로그인 추가"))
                .isEqualTo("📦 team1/backend (커밋 3개)");
        String label = QuestionMessageFormatter.itemLabel("가".repeat(150), null);
        assertThat(label).hasSize(100).endsWith("…");
    }

    @Test
    void 이천자를_넘으면_질문을_잘라_한도를_지킨다() {
        String question = "질".repeat(1000);
        String text = QuestionMessageFormatter.format(7L, "항목", null, question,
                new QuestionTargets(List.of(1001L), List.of()), List.of());
        assertThat(text).hasSizeLessThanOrEqualTo(2000).contains("질".repeat(1000));   // 한도 안이면 그대로

        // 개발자 줄이 길어 질문까지 합치면 한도를 넘으면 질문을 자른다. 개발자·커밋 줄은 그대로 남는다
        List<String> manyNames = IntStream.rangeClosed(1, 30).mapToObj(i -> "developer-name-" + i).toList();
        String cut = QuestionMessageFormatter.format(7L, "항목", null, question,
                new QuestionTargets(List.of(1001L), manyNames),
                List.of(new QuestionCommit(SHA_A, "https://github.com/t/b/commit/" + SHA_A)));

        assertThat(cut).hasSize(2000)
                .contains("질…\n→ <@1001> · developer-name-1 (디스코드 미연동)")
                .endsWith("developer-name-30 (디스코드 미연동)\n관련 커밋: [abc1234](https://github.com/t/b/commit/" + SHA_A + ")");
    }

    @Test
    void 자를_때_이스케이프_문자와_서로게이트_쌍을_가르지_않는다() {
        assertThat(QuestionMessageFormatter.cut("ab\\*cd", 4)).isEqualTo("ab…");
        assertThat(QuestionMessageFormatter.cut("ab\\\\cd", 5)).isEqualTo("ab\\\\…");
        assertThat(QuestionMessageFormatter.cut("a😀b", 3)).isEqualTo("a…");
        assertThat(QuestionMessageFormatter.cut("abc", 3)).isEqualTo("abc");
    }
}
