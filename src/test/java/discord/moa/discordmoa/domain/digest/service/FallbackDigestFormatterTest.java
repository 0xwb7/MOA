package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.batch;
import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.commit;
import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.repo;
import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

class FallbackDigestFormatterTest {

    private final FallbackDigestFormatter formatter = new FallbackDigestFormatter();
    private final Team team = team(1L, "1팀", 100L, 7L);
    private final TrackedRepo repo = repo(1L, team, "0xwb7/springSecurityExample");

    @Test
    void 항목은_레포명_커밋수_커밋_첫줄_변경내역_링크로_구성된다() {
        ChangeBatch batch = batch(1L, repo, "base", "head", "로그인 API 추가", "비밀번호 암호화 적용", "테스트 코드 수정");

        String text = formatter.itemText(repo.getFullName(), List.of(batch));

        assertThat(text).isEqualTo("""
                📦 0xwb7/springSecurityExample (커밋 3개)
                - 로그인 API 추가
                - 비밀번호 암호화 적용
                - 테스트 코드 수정
                🔗 [변경 내역 보기](https://github.com/0xwb7/springSecurityExample/compare/base...head)""");
    }

    @Test
    void 커밋이_10개를_넘으면_10개만_보여주고_외_N개로_표시한다() {
        List<CommitSnapshot> commits = IntStream.rangeClosed(1, 12).mapToObj(i -> commit("s" + i, "커밋 " + i)).toList();

        String text = formatter.itemText(repo.getFullName(), List.of(batch(1L, repo, "b", "h", false, false, commits)));

        assertThat(text).contains("(커밋 12개)", "- 커밋 1\n", "- 커밋 10\n", "- 외 2개\n");
        assertThat(text).doesNotContain("- 커밋 11", "- 커밋 12");
    }

    @Test
    void 커밋이_정확히_10개면_외_N개를_붙이지_않는다() {
        List<CommitSnapshot> commits = IntStream.rangeClosed(1, 10).mapToObj(i -> commit("s" + i, "커밋 " + i)).toList();

        String text = formatter.itemText(repo.getFullName(), List.of(batch(1L, repo, "b", "h", false, false, commits)));

        assertThat(text).contains("- 커밋 10\n").doesNotContain("외 ");
    }

    @Test
    void 여러_줄_커밋_메시지는_첫_줄만_보여준다() {
        ChangeBatch batch = batch(1L, repo, "b", "h", "로그인 API 추가\n\n- JWT 발급\n- 리프레시 토큰");

        String text = formatter.itemText(repo.getFullName(), List.of(batch));

        assertThat(text).contains("- 로그인 API 추가\n").doesNotContain("JWT", "리프레시");
    }

    @Test
    void 긴_첫_줄은_100자로_자르고_말줄임표를_붙인다() {
        assertThat(FallbackDigestFormatter.firstLine("가".repeat(150))).isEqualTo("가".repeat(100) + "…");
        assertThat(FallbackDigestFormatter.firstLine("   ")).isEqualTo("(메시지 없음)");
        assertThat(FallbackDigestFormatter.firstLine(null)).isEqualTo("(메시지 없음)");
    }

    @Test
    void 커밋_메시지의_마크다운은_이스케이프한다() {
        assertThat(FallbackDigestFormatter.firstLine("**긴급** 수정")).isEqualTo("\\*\\*긴급\\*\\* 수정");
    }

    @Test
    void force_push면_경고를_표시하고_commits_링크를_건다() {
        ChangeBatch batch = batch(1L, repo, "base", "head", true, false, List.of(commit("s1", "재작성")));

        String text = formatter.itemText(repo.getFullName(), List.of(batch));

        assertThat(text).contains("⚠️ 히스토리가 변경되어 최근 커밋만 표시해요\n")
                .doesNotContain("일부 커밋만")
                .endsWith("(https://github.com/0xwb7/springSecurityExample/commits/head)");
    }

    @Test
    void 잘린_수집이면_일부만_표시한다고_알린다() {
        ChangeBatch batch = batch(1L, repo, "base", "head", false, true, List.of(commit("s1", "m")));

        String text = formatter.itemText(repo.getFullName(), List.of(batch));

        assertThat(text).contains("⚠️ 일부 커밋만 표시해요\n").doesNotContain("히스토리");
    }

    @Test
    void base가_null이면_commits_페이지_링크를_건다() {
        ChangeBatch batch = batch(1L, repo, null, "head", "첫 커밋");

        String text = formatter.itemText(repo.getFullName(), List.of(batch));

        assertThat(text).endsWith("(https://github.com/0xwb7/springSecurityExample/commits/head)");
    }

    @Test
    void 같은_레포의_배치_여러_개는_커밋을_이어붙이고_첫_base부터_마지막_head까지_링크한다() {
        ChangeBatch first = batch(1L, repo, "a", "b", "첫 회차 커밋");
        ChangeBatch second = batch(2L, repo, "b", "c", "둘째 회차 커밋 1", "둘째 회차 커밋 2");

        String text = formatter.itemText(repo.getFullName(), List.of(first, second));

        assertThat(text).contains("(커밋 3개)", "- 첫 회차 커밋\n- 둘째 회차 커밋 1\n- 둘째 회차 커밋 2\n")
                .endsWith("/compare/a...c)");
    }

    @Test
    void 헤더는_PM을_멘션하고_변경_없음_문구는_멘션하지_않는다() {
        assertThat(formatter.header("1팀", 7L)).isEqualTo("""
                <@7> 지난 12시간 동안 '1팀'에 변경이 있었어요
                요약을 만들지 못해 변경 목록을 보내드려요""");
        assertThat(formatter.noChange("1팀")).isEqualTo("지난 12시간 동안 '1팀'에 변경 사항이 없어요")
                .doesNotContain("<@");
    }
}
