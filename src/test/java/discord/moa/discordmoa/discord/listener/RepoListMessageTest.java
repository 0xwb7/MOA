package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.RepoDeactivation;
import discord.moa.discordmoa.domain.team.service.RepoRegistration;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoListMessageTest {

    private final Team team = new Team("1팀", 100L, 7L);

    @Test
    void 레포마다_등록일과_마지막_수집_시각_뒤에_어디까지_수집했는지_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/my_backend", "main", "abcdef1234567890abcdef1234567890abcdef12",
                555L, Instant.parse("2026-11-23T00:00:00Z"));
        ReflectionTestUtils.setField(repo, "lastCollectedAt", Instant.parse("2026-11-23T11:30:00Z"));

        String text = RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo)));

        assertThat(text).contains("'1팀' 레포 목록 (1개)")
                .contains("**team1/my_backend**")
                .contains("등록 11/23 09:00 · 마지막 수집 11/23 20:30 (`abcdef1`까지)\n")
                .doesNotContain("abcdef12")
                .doesNotContain("커서");
    }

    @Test
    void 수집한_적이_없으면_어디부터_시작하는지_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", "abcdef1234567890abcdef1234567890abcdef12", 555L);

        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo))))
                .contains("등록 기록 없음 · 마지막 수집 없음 (`abcdef1`부터 시작)\n");
    }

    @Test
    void 수집한_적도_커서도_없으면_첫_커밋부터_시작한다고_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", null, 555L);

        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo))))
                .contains("등록 기록 없음 · 마지막 수집 없음 (첫 커밋부터 시작)\n");
    }

    @Test
    void 수집했지만_커서가_없으면_시각만_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", null, 555L);
        ReflectionTestUtils.setField(repo, "lastCollectedAt", Instant.parse("2026-11-23T11:30:00Z"));

        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo))))
                .contains("마지막 수집 11/23 20:30\n");
    }

    @Test
    void 레포가_없으면_레포등록을_안내한다() {
        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of())))
                .isEqualTo("📦 '1팀' 팀에 등록된 레포가 없어요. /레포등록 으로 레포를 등록해 주세요.");
    }

    @Test
    void 재등록과_이동은_등록_응답에서_구분된다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", "a".repeat(40), 555L);

        assertThat(TeamCommandHandler.describeRegistration(RepoRegistration.of(repo, RepoRegistration.Kind.REACTIVATED)))
                .contains("다시 등록했어요", "해제돼 있던 동안의 변경은 건너뛰고");
        assertThat(TeamCommandHandler.describeRegistration(
                new RepoRegistration(repo, RepoRegistration.Kind.MOVED, "2팀")))
                .contains("'2팀' 팀에서 이 팀으로 옮겨 등록했어요");
        assertThat(TeamCommandHandler.describeRegistration(RepoRegistration.of(repo, RepoRegistration.Kind.NEW)))
                .contains("레포가 등록됐어요");
    }

    @Test
    void 해제_응답은_보내지_않게_된_변경이_있을_때만_건수를_알려준다() {
        assertThat(TeamCommandHandler.describeDeactivation(new RepoDeactivation("1팀", "team1/app", 3)))
                .isEqualTo("✅ team1/app 레포 등록을 해제했어요. 이제 이 레포의 변경은 수집하지 않아요.\n"
                        + "아직 보내지 않은 변경 3건은 보내지 않아요.\n"
                        + "다시 수집하려면 /레포등록 을 실행해 주세요.");
        assertThat(TeamCommandHandler.describeDeactivation(new RepoDeactivation("1팀", "team1/app", 0)))
                .isEqualTo("✅ team1/app 레포 등록을 해제했어요. 이제 이 레포의 변경은 수집하지 않아요.\n"
                        + "다시 수집하려면 /레포등록 을 실행해 주세요.");
    }

    @Test
    void 같은_이름의_이전_등록을_정리했으면_등록_응답에_알려준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", "a".repeat(40), 555L);

        assertThat(TeamCommandHandler.describeRegistration(
                RepoRegistration.of(repo, RepoRegistration.Kind.NEW).withCleanedUpStale(true)))
                .startsWith("✅ team1/app 레포가 등록됐어요.")
                .endsWith("\n같은 이름의 이전 레포 등록을 정리했어요.");
        assertThat(TeamCommandHandler.describeRegistration(RepoRegistration.of(repo, RepoRegistration.Kind.NEW)))
                .doesNotContain("정리");
    }
}
