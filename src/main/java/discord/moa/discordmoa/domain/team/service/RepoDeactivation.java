package discord.moa.discordmoa.domain.team.service;

/**
 * /레포해제 결과.
 */
public record RepoDeactivation(
        String teamName,
        String fullName
) {
}
