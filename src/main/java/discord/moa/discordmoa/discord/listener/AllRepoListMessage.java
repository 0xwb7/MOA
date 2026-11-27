package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.service.TrackedRepoService.TeamRepos;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.ArrayList;
import java.util.List;

/**
 * /레포전체목록 응답(에페메럴) 텍스트. 2000자를 넘으면 팀 단위로 나눠 여러 메시지로 만든다.
 */
final class AllRepoListMessage {

    static final int MAX_LENGTH = 2000;   // Discord 메시지 최대 길이
    static final String NO_TEAM = "등록된 팀이 없어요";
    static final String NO_REPO = "- ⚠️ 등록된 레포 없음";

    private AllRepoListMessage() {
    }

    static List<String> format(List<TeamRepos> teams) {
        if (teams.isEmpty()) {
            return List.of(NO_TEAM);
        }
        int repoCount = teams.stream().mapToInt(team -> team.repoFullNames().size()).sum();
        String header = "📦 전체 레포 목록 (팀 " + teams.size() + "개 · 레포 " + repoCount + "개)";

        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (TeamRepos team : teams) {
            for (String block : blocks(team)) {
                if (current.length() + 2 + block.length() <= MAX_LENGTH) {
                    current.append("\n\n").append(block);
                } else {
                    messages.add(current.toString());
                    current = new StringBuilder(block);
                }
            }
        }
        messages.add(current.toString());
        return messages;
    }

    /**
     * 팀 하나의 텍스트. 보통은 한 덩어리이고, 팀 하나만으로 2000자를 넘을 때만 레포 줄 단위로 나눈다
     * (이어지는 덩어리에는 팀 줄을 "(이어서)"로 다시 붙인다).
     */
    private static List<String> blocks(TeamRepos team) {
        String name = MarkdownSanitizer.escape(team.teamName());
        String channel = "  <#" + team.channelId() + ">";
        List<String> lines = team.repoFullNames().isEmpty()
                ? List.of(NO_REPO)
                : team.repoFullNames().stream().map(fullName -> "- <https://github.com/" + fullName + ">").toList();

        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder(name).append(" 레포").append(channel);
        int linesInBlock = 0;
        for (String line : lines) {
            if (linesInBlock > 0 && block.length() + 1 + line.length() > MAX_LENGTH) {
                blocks.add(block.toString());
                block = new StringBuilder(name).append(" 레포 (이어서)").append(channel);
                linesInBlock = 0;
            }
            block.append('\n').append(line);
            linesInBlock++;
        }
        blocks.add(block.toString());
        return blocks;
    }
}
