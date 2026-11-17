package discord.moa.discordmoa.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubRepo(
        Long id,

        @JsonProperty("full_name")
        String fullName,

        @JsonProperty("default_branch")
        String defaultBranch
) {
}
