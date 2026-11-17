package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;

public record RepoCollectResult(
        String repoFullName,
        Outcome outcome,
        int commitCount,
        int fileCount,
        boolean forcePushDetected,
        boolean truncated
) {

    public enum Outcome {
        COLLECTED, NO_CHANGE, FAILED
    }

    static RepoCollectResult collected(String repoFullName, ChangeBatch batch) {
        return new RepoCollectResult(repoFullName, Outcome.COLLECTED,
                batch.getCommits().size(), batch.getFiles().size(),
                batch.isForcePushDetected(), batch.isTruncated());
    }

    static RepoCollectResult noChange(String repoFullName) {
        return new RepoCollectResult(repoFullName, Outcome.NO_CHANGE, 0, 0, false, false);
    }

    static RepoCollectResult failed(String repoFullName) {
        return new RepoCollectResult(repoFullName, Outcome.FAILED, 0, 0, false, false);
    }
}
