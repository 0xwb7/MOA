package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;

public record RepoCollectResult(
        String repoFullName,
        Outcome outcome,
        int commitCount,
        int fileCount,
        boolean forcePushDetected,
        boolean truncated,
        String failureReason    // FAILED일 때만. 사용자에게 보여줄 원인
) {

    public enum Outcome {
        COLLECTED, NO_CHANGE, FAILED
    }

    static RepoCollectResult collected(String repoFullName, ChangeBatch batch) {
        return new RepoCollectResult(repoFullName, Outcome.COLLECTED,
                batch.getCommits().size(), batch.getFiles().size(),
                batch.isForcePushDetected(), batch.isTruncated(), null);
    }

    static RepoCollectResult noChange(String repoFullName) {
        return new RepoCollectResult(repoFullName, Outcome.NO_CHANGE, 0, 0, false, false, null);
    }

    static RepoCollectResult failed(String repoFullName, String failureReason) {
        return new RepoCollectResult(repoFullName, Outcome.FAILED, 0, 0, false, false, failureReason);
    }
}
