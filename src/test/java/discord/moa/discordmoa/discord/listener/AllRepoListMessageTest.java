package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.service.TrackedRepoService.TeamRepos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AllRepoListMessageTest {

    @Test
    void 팀별로_채널_멘션과_레포_주소를_링크_미리보기_없이_보여준다() {
        List<String> messages = AllRepoListMessage.format(List.of(
                new TeamRepos("1팀", 100L, List.of("0xwb7/front", "0xwb7/back")),
                new TeamRepos("2팀", 200L, List.of("0xwb7/app"))));

        assertThat(messages).containsExactly("""
                📦 전체 레포 목록 (팀 2개 · 레포 3개)

                1팀 레포  <#100>
                - <https://github.com/0xwb7/front>
                - <https://github.com/0xwb7/back>

                2팀 레포  <#200>
                - <https://github.com/0xwb7/app>""");
    }

    @Test
    void 레포가_없는_팀은_등록된_레포_없음으로_보여주고_팀_수에는_센다() {
        List<String> messages = AllRepoListMessage.format(List.of(
                new TeamRepos("1팀", 100L, List.of("0xwb7/front")),
                new TeamRepos("2팀", 200L, List.of())));

        assertThat(messages).containsExactly("""
                📦 전체 레포 목록 (팀 2개 · 레포 1개)

                1팀 레포  <#100>
                - <https://github.com/0xwb7/front>

                2팀 레포  <#200>
                - ⚠️ 등록된 레포 없음""");
    }

    @Test
    void 등록된_팀이_없으면_안내만_보낸다() {
        assertThat(AllRepoListMessage.format(List.of())).containsExactly("등록된 팀이 없어요");
    }

    @Test
    void 팀_이름의_마크다운_문자는_이스케이프한다() {
        List<String> messages = AllRepoListMessage.format(List.of(new TeamRepos("**굵게**팀", 100L, List.of())));

        assertThat(messages.getFirst()).contains("\\*\\*굵게\\*\\*팀 레포  <#100>");
    }

    @Test
    void 이천자를_넘으면_팀을_자르지_않고_팀_단위로_나눈다() {
        // 팀 하나는 약 700자: 세 팀이 한 메시지에 들어가지 않는다
        List<TeamRepos> teams = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new TeamRepos(i + "팀", 100L + i, repos(i, 15)))
                .toList();

        List<String> messages = AllRepoListMessage.format(teams);

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(message -> assertThat(message.length()).isLessThanOrEqualTo(2000));
        assertThat(messages.getFirst()).startsWith("📦 전체 레포 목록 (팀 5개 · 레포 75개)");
        assertThat(messages.subList(1, messages.size())).noneMatch(message -> message.contains("📦"));
        assertThat(String.join("\n\n", messages)).doesNotContain("(이어서)");
        for (TeamRepos team : teams) {
            // 팀 줄과 그 팀의 레포 전부가 같은 메시지에 있다
            String teamLine = team.teamName() + " 레포  <#" + team.channelId() + ">";
            assertThat(messages).filteredOn(message -> message.contains(teamLine)).singleElement()
                    .satisfies(message -> team.repoFullNames()
                            .forEach(repo -> assertThat(message).contains("- <https://github.com/" + repo + ">")));
        }
    }

    @Test
    void 팀_하나만으로_이천자를_넘으면_레포_줄_단위로_나누고_팀_줄을_이어서로_다시_붙인다() {
        TeamRepos big = new TeamRepos("1팀", 100L, repos(1, 80));

        List<String> messages = AllRepoListMessage.format(List.of(big));

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(message -> assertThat(message.length()).isLessThanOrEqualTo(2000));
        assertThat(messages.subList(1, messages.size()))
                .allSatisfy(message -> assertThat(message).startsWith("1팀 레포 (이어서)  <#100>\n- <https://github.com/"));
        // 빠지거나 겹친 레포가 없다
        String all = String.join("\n", messages);
        big.repoFullNames().forEach(repo -> assertThat(all).containsOnlyOnce("<https://github.com/" + repo + ">"));
    }

    private static List<String> repos(int team, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "growthon-team-" + team + "/service-repository-" + i)
                .toList();
    }
}
