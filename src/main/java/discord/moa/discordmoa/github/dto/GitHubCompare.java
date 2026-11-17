package discord.moa.discordmoa.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GitHubCompare(
        String status,  // ahead, behind, identical, diverged
        @JsonProperty("total_commits") int totalCommits,
        List<GitHubCommit> commits,
        List<GitHubFile> files
) {
}
