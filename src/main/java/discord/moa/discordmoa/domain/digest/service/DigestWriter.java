package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DigestWriter {

    static final Duration STALE_PENDING_AFTER = Duration.ofMinutes(10);

    private final TeamRepository teamRepository;
    private final ChangeBatchRepository changeBatchRepository;
    private final DigestRepository digestRepository;
    private final DigestItemRepository digestItemRepository;
    private final FallbackDigestFormatter formatter;

    @Transactional
    public PreparedDigest prepare(Long teamId, Instant now) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("팀을 찾을 수 없어요: teamId=" + teamId));
        closeOrRejectPending(teamId, now);

        List<ChangeBatch> batches = changeBatchRepository
                .findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(teamId, ChangeBatchStatus.COLLECTED);

        if (batches.isEmpty()) {
            Digest digest = digestRepository.save(new Digest(team, DigestType.NO_CHANGE, now));
            return new PreparedDigest(digest.getId(), DigestType.NO_CHANGE, team.getName(),
                    team.getDiscordChannelId(), team.getPmDiscordUserId(), formatter.noChange(team.getName()), List.of());
        }

        Digest digest = digestRepository.save(new Digest(team, DigestType.FALLBACK, now));
        List<PreparedDigest.Item> items = new ArrayList<>();
        for (List<ChangeBatch> repoBatches : groupByRepo(batches).values()) {
            TrackedRepo repo = repoBatches.getFirst().getTrackedRepo();
            String text = formatter.itemText(repo.getFullName(), repoBatches);
            List<String> commitShas = repoBatches.stream()
                    .flatMap(batch -> batch.getCommits().stream())
                    .map(CommitSnapshot::sha)
                    .toList();

            DigestItem item = digestItemRepository.save(new DigestItem(digest, repo, text, commitShas));
            items.add(new PreparedDigest.Item(item.getId(), text,
                    repoBatches.stream().map(ChangeBatch::getId).toList()));
        }

        return new PreparedDigest(digest.getId(), DigestType.FALLBACK, team.getName(), team.getDiscordChannelId(),
                team.getPmDiscordUserId(), formatter.header(team.getName(), team.getPmDiscordUserId()), items);
    }

    @Transactional
    public void markSent(PreparedDigest prepared, Map<Long, Long> messageIdByItemId, Instant sentAt) {
        markDelivered(prepared, messageIdByItemId);
        findDigest(prepared).markSent(sentAt);
    }

    @Transactional
    public void markFailed(PreparedDigest prepared, Map<Long, Long> deliveredMessageIdByItemId, String reason) {
        markDelivered(prepared, deliveredMessageIdByItemId);
        findDigest(prepared).markFailed(reason);
    }

    private void markDelivered(PreparedDigest prepared, Map<Long, Long> messageIdByItemId) {
        for (PreparedDigest.Item prepItem : prepared.items()) {
            Long messageId = messageIdByItemId.get(prepItem.itemId());
            if (messageId == null) {
                continue;
            }
            DigestItem item = digestItemRepository.findById(prepItem.itemId())
                    .orElseThrow(() -> new IllegalStateException("발송 항목을 찾을 수 없어요: " + prepItem.itemId()));
            item.markDelivered(messageId);
            changeBatchRepository.findAllById(prepItem.changeBatchIds())
                    .forEach(batch -> batch.markSent(item));
        }
    }

    private void closeOrRejectPending(Long teamId, Instant now) {
        for (Digest pending : digestRepository.findAllByTeamIdAndStatus(teamId, DigestStatus.PENDING)) {
            if (pending.getCreatedAt().isAfter(now.minus(STALE_PENDING_AFTER))) {
                throw new IllegalStateException("이 팀의 발송이 이미 진행 중이에요. 잠시 후 다시 시도해 주세요.");
            }
            pending.markFailed("발송 도중 중단됐어요 (서버 재시작 등). 미발송 변경은 다음 회차에 다시 포함돼요.");
        }
    }

    private Digest findDigest(PreparedDigest prepared) {
        return digestRepository.findById(prepared.digestId())
                .orElseThrow(() -> new IllegalStateException("발송 기록을 찾을 수 없어요: " + prepared.digestId()));
    }

    private static Map<Long, List<ChangeBatch>> groupByRepo(List<ChangeBatch> batches) {
        Map<Long, List<ChangeBatch>> byRepo = new LinkedHashMap<>();
        for (ChangeBatch batch : batches) {
            byRepo.computeIfAbsent(batch.getTrackedRepo().getId(), id -> new ArrayList<>()).add(batch);
        }
        return byRepo;
    }
}
