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
import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigestWriter {

    static final Duration STALE_PENDING_AFTER = Duration.ofMinutes(10);

    private final TeamRepository teamRepository;
    private final ChangeBatchRepository changeBatchRepository;
    private final DigestRepository digestRepository;
    private final DigestItemRepository digestItemRepository;
    private final FallbackDigestFormatter formatter;
    private final SummaryDigestFormatter summaryFormatter;

    /**
     * LLM 요약(트랜잭션 밖)을 시작하기 전에 확인한다. 진행 중인 발송이 있으면 요약 비용을 쓰지 않고 바로 거절한다.
     */
    @Transactional(readOnly = true)
    public void checkNotInProgress(Long teamId, Instant now) {
        for (Digest pending : digestRepository.findAllByTeamIdAndStatus(teamId, DigestStatus.PENDING)) {
            if (pending.getCreatedAt().isAfter(now.minus(STALE_PENDING_AFTER))) {
                throw new IllegalStateException("이 팀의 발송이 이미 진행 중이에요. 잠시 후 다시 시도해 주세요.");
            }
        }
    }

    /**
     * 요약이 성공했으면 LLM Digest를, 아니면 FALLBACK(또는 NO_CHANGE) Digest를 만든다.
     */
    @Transactional
    public PreparedDigest prepare(Long teamId, Instant now, SummaryResult summary) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("팀을 찾을 수 없어요: teamId=" + teamId));
        closeOrRejectPending(teamId, now);

        if (summary.succeeded()) {
            Optional<PreparedDigest> llm = prepareSummary(team, now, summary);
            if (llm.isPresent()) {
                return llm.get();
            }
            log.warn("요약하는 동안 배치 상태가 바뀌어 변경 목록(fallback)으로 보내요: team={}, batchIds={}",
                    team.getName(), summary.changeBatchIds());
        }

        List<ChangeBatch> batches = changeBatchRepository
                .findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(teamId, ChangeBatchStatus.COLLECTED);

        if (batches.isEmpty()) {
            Digest digest = digestRepository.save(new Digest(team, DigestType.NO_CHANGE, now));
            return new PreparedDigest(digest.getId(), DigestType.NO_CHANGE, team.getName(),
                    team.getDiscordChannelId(), team.getPmDiscordUserId(), formatter.noChange(team.getName()),
                    List.of(), List.of());
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
                team.getPmDiscordUserId(), formatter.header(team.getName(), team.getPmDiscordUserId()), items,
                batches.stream().map(ChangeBatch::getId).toList());
    }

    /**
     * 요약한 배치가 모두 아직 COLLECTED일 때만 LLM Digest를 만든다.
     * 요약하는 동안 새로 수집된 배치는 포함하지 않고 다음 회차로 넘긴다.
     */
    private Optional<PreparedDigest> prepareSummary(Team team, Instant now, SummaryResult summary) {
        List<ChangeBatch> batches = changeBatchRepository.findAllById(summary.changeBatchIds());
        boolean allCollected = batches.size() == summary.changeBatchIds().size()
                && batches.stream().allMatch(batch -> batch.getStatus() == ChangeBatchStatus.COLLECTED);
        if (!allCollected) {
            return Optional.empty();
        }

        Map<String, TrackedRepo> repoBySha = new HashMap<>();
        for (ChangeBatch batch : batches) {
            batch.getCommits().forEach(commit -> repoBySha.put(commit.sha(), batch.getTrackedRepo()));
        }

        Digest digest = digestRepository.save(new Digest(team, DigestType.LLM, now));
        List<PreparedDigest.Item> items = new ArrayList<>();
        for (SummaryItem summaryItem : summary.items()) {
            Set<TrackedRepo> repos = new HashSet<>();
            summaryItem.commitShas().forEach(sha -> repos.add(repoBySha.get(sha)));
            TrackedRepo repo = repos.size() == 1 ? repos.iterator().next() : null;

            String text = summaryFormatter.itemText(summaryItem);
            DigestItem item = digestItemRepository.save(new DigestItem(digest, repo, text, summaryItem.commitShas()));
            items.add(new PreparedDigest.Item(item.getId(), text, List.of()));
        }

        return Optional.of(new PreparedDigest(digest.getId(), DigestType.LLM, team.getName(), team.getDiscordChannelId(),
                team.getPmDiscordUserId(), summaryFormatter.header(team.getName(), team.getPmDiscordUserId()), items,
                summary.changeBatchIds()));
    }

    @Transactional
    public void markSent(PreparedDigest prepared, Map<Long, Long> messageIdByItemId, Instant sentAt) {
        Digest digest = findDigest(prepared);
        markDelivered(digest, prepared, messageIdByItemId);
        if (prepared.type() == DigestType.LLM) {
            // LLM 항목은 배치와 1:1이 아니라서, 전부 전달됐을 때만 담긴 배치를 한꺼번에 SENT로 바꾼다
            changeBatchRepository.findAllById(prepared.changeBatchIds())
                    .forEach(batch -> batch.markSent(digest, null));
        }
        digest.markSent(sentAt);
    }

    /**
     * FALLBACK은 전달된 항목의 배치만 SENT로 바꾼다. LLM은 일부만 전달됐으면 배치를 모두 COLLECTED로 둔다.
     */
    @Transactional
    public void markFailed(PreparedDigest prepared, Map<Long, Long> deliveredMessageIdByItemId, String reason) {
        Digest digest = findDigest(prepared);
        markDelivered(digest, prepared, deliveredMessageIdByItemId);
        digest.markFailed(reason);
    }

    private void markDelivered(Digest digest, PreparedDigest prepared, Map<Long, Long> messageIdByItemId) {
        for (PreparedDigest.Item prepItem : prepared.items()) {
            Long messageId = messageIdByItemId.get(prepItem.itemId());
            if (messageId == null) {
                continue;
            }
            DigestItem item = digestItemRepository.findById(prepItem.itemId())
                    .orElseThrow(() -> new IllegalStateException("발송 항목을 찾을 수 없어요: " + prepItem.itemId()));
            item.markDelivered(messageId);
            changeBatchRepository.findAllById(prepItem.changeBatchIds())
                    .forEach(batch -> batch.markSent(digest, item));
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
