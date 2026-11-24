package discord.moa.discordmoa.domain.collect.entity;

/**
 * @param classPath 컨트롤러 파일의 클래스 레벨 @RequestMapping 경로 (예: "/api/auth"). 메서드만 추가된 diff에는
 *                  클래스 경로가 보이지 않아서, 수집할 때 head 시점 파일에서 뽑아 둔다. 없거나 못 뽑으면 null
 */
public record FileChange(
        String commitSha,       // compare로 모은 전체 diff면 null, 커밋별로 모은 경우 해당 커밋 SHA
        String filename,
        String status,
        int additions,
        int deletions,
        String patch,           // 바이너리 등 diff가 없으면 null
        boolean patchTruncated,
        String classPath
) {

    public FileChange(String commitSha, String filename, String status, int additions, int deletions,
                      String patch, boolean patchTruncated) {
        this(commitSha, filename, status, additions, deletions, patch, patchTruncated, null);
    }

    public FileChange withClassPath(String classPath) {
        return new FileChange(commitSha, filename, status, additions, deletions, patch, patchTruncated, classPath);
    }
}
