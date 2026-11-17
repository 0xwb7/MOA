package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

final class DigestFixtures {

    static final Instant T = Instant.parse("2026-11-17T03:00:00Z");

    private DigestFixtures() {
    }

    static Team team(Long id, String name, Long channelId, Long pmId) {
        Team team = new Team(name, channelId, pmId);
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    static TrackedRepo repo(Long id, Team team, String fullName) {
        TrackedRepo repo = new TrackedRepo(team, id * 100, fullName, "main", null, 555L);
        ReflectionTestUtils.setField(repo, "id", id);
        return repo;
    }

    static ChangeBatch batch(Long id, TrackedRepo repo, String base, String head, String... messages) {
        List<CommitSnapshot> commits = IntStream.range(0, messages.length)
                .mapToObj(i -> commit(head + "-" + i, messages[i]))
                .toList();
        return batch(id, repo, base, head, false, false, commits);
    }

    static ChangeBatch batch(Long id, TrackedRepo repo, String base, String head,
                             boolean forcePush, boolean truncated, List<CommitSnapshot> commits) {
        ChangeBatch batch = new ChangeBatch(repo, base, head, T, forcePush, truncated, commits, List.of());
        ReflectionTestUtils.setField(batch, "id", id);
        return batch;
    }

    static CommitSnapshot commit(String sha, String message) {
        return new CommitSnapshot(sha, message, "alice", 11L, "Alice", T, "https://github.com/x/y/commit/" + sha);
    }
}
