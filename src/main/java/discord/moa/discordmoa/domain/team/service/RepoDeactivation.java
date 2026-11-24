package discord.moa.discordmoa.domain.team.service;

/**
 * /레포해제 결과.
 *
 * @param discardedCommitCount 아직 보내지 않아서 이번 해제로 보내지 않게 된 변경(커밋) 수
 */
public record RepoDeactivation(
        String teamName,
        String fullName,
        int discardedCommitCount
) {
}
