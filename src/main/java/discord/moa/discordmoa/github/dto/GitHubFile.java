package discord.moa.discordmoa.github.dto;

public record GitHubFile(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch
) {
}
