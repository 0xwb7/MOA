package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
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
    void 레포마다_등록일_마지막_수집_시각_커서_앞_7자리를_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/my_backend", "main", "abcdef1234567890abcdef1234567890abcdef12",
                555L, Instant.parse("2026-11-23T00:00:00Z"));
        ReflectionTestUtils.setField(repo, "lastCollectedAt", Instant.parse("2026-11-23T11:30:00Z"));

        String text = RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo)));

        assertThat(text).contains("'1팀' 레포 목록 (1개)")
                .contains("**team1/my_backend**")
                .contains("등록 11/23 09:00 · 마지막 수집 11/23 20:30 · 커서 `abcdef1`")
                .doesNotContain("abcdef12");
    }

    @Test
    void 기록이_없는_값은_없음으로_보여준다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", null, 555L);

        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of(repo))))
                .contains("등록 기록 없음 · 마지막 수집 아직 없음 · 커서 없음 (첫 커밋부터 수집)");
    }

    @Test
    void 레포가_없으면_레포등록을_안내한다() {
        assertThat(RepoListMessage.format(new TrackedRepoService.RepoList("1팀", List.of())))
                .isEqualTo("📦 '1팀' 팀에 등록된 레포가 없어요. /레포등록 으로 레포를 등록해 주세요.");
    }

    @Test
    void 재등록과_이동은_등록_응답에서_구분된다() {
        TrackedRepo repo = new TrackedRepo(team, 1L, "team1/app", "main", "a".repeat(40), 555L);

        assertThat(SlashCommandListener.describeRegistration(RepoRegistration.of(repo, RepoRegistration.Kind.REACTIVATED)))
                .contains("다시 등록했어요", "해제돼 있던 동안의 변경은 건너뛰고");
        assertThat(SlashCommandListener.describeRegistration(
                new RepoRegistration(repo, RepoRegistration.Kind.MOVED, "2팀")))
                .contains("'2팀' 팀에서 이 팀으로 옮겨 등록했어요");
        assertThat(SlashCommandListener.describeRegistration(RepoRegistration.of(repo, RepoRegistration.Kind.NEW)))
                .contains("레포가 등록됐어요");
    }
}
