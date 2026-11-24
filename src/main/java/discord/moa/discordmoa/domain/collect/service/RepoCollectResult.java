package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;

public record RepoCollectResult(
        String repoFullName,
        Outcome outcome,
        int commitCount,
        int fileCount,
        boolean forcePushDetected,
        boolean truncated,
        String failureReason    // FAILED·DEACTIVATED일 때만. 사용자에게 보여줄 원인
) {

    public enum Outcome {
        COLLECTED, NO_CHANGE, FAILED,
        DEACTIVATED   // 같은 이름의 다른 레포(삭제 후 재생성)라 등록을 해제함 (운영 알림은 따로 보냄)
    }

    static RepoCollectResult collected(String repoFullName, ChangeBatch batch) {
        return new RepoCollectResult(repoFullName, Outcome.COLLECTED,
                batch.getCommits().size(), batch.getFiles().size(),
                batch.isForcePushDetected(), batch.isTruncated(), null);
    }

    static RepoCollectResult noChange(String repoFullName) {
        return new RepoCollectResult(repoFullName, Outcome.NO_CHANGE, 0, 0, false, false, null);
    }

    static RepoCollectResult deactivated(String repoFullName, String reason) {
        return new RepoCollectResult(repoFullName, Outcome.DEACTIVATED, 0, 0, false, false, reason);
    }

    static RepoCollectResult failed(String repoFullName, String failureReason) {
        return new RepoCollectResult(repoFullName, Outcome.FAILED, 0, 0, false, false, failureReason);
    }
}
