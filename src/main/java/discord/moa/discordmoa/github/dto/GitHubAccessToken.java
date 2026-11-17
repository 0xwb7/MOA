package discord.moa.discordmoa.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record GitHubAccessToken(
        String token,

        @JsonProperty("expires_at")
        Instant expiresAt
) {
}
