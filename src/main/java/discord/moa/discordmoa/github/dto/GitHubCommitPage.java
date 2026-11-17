package discord.moa.discordmoa.github.dto;

import java.util.List;

public record GitHubCommitPage(List<GitHubCommit> commits, boolean hasNext) {
}
