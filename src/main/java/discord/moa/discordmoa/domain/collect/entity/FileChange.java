package discord.moa.discordmoa.domain.collect.entity;

public record FileChange(
        String commitSha,       // compare로 모은 전체 diff면 null, 커밋별로 모은 경우 해당 커밋 SHA
        String filename,
        String status,
        int additions,
        int deletions,
        String patch,           // 바이너리 등 diff가 없으면 null
        boolean patchTruncated
) {
}
