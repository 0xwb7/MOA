package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 회차(Digest)와 배치(ChangeBatch)의 상태 전이를 DB에 기록한다.
 * <pre>
 * Digest:      (없음) → PREPARING → READY → SENT / FAILED
 * ChangeBatch: COLLECTED → PREPARED → SENT,  발송 실패·준비 취소 시 PREPARED → COLLECTED
 * </pre>
 * 같은 회차를 두 번 처리하지 않는 정확성은 유일 제약(팀, scheduledFor)과 조건부 UPDATE로 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestWriter {

    static final String LATE_COLLECTED_REASON = "요약을 시작한 뒤에 수집된 변경이라 요약하지 않았어요";
    static final String BATCHES_CHANGED_REASON = "요약하는 동안 배치 상태가 바뀌었어요";

    private final TeamRepository teamRepository;
    private final ChangeBatchRepository changeBatchRepository;
    private final DigestRepository digestRepository;
    private final DigestItemRepository digestItemRepository;
    private final FallbackDigestFormatter formatter;
    private final SummaryDigestFormatter summaryFormatter;

    /**
     * 회차를 선점한다. 없으면 PREPARING으로 만들고, 이미 있으면 그 상태를 돌려준다.
     * 트랜잭션 밖에서 호출한다 (유일 제약 위반이 나도 다시 조회할 수 있도록).
     */
    public CycleClaim claimCycle(Long teamId, Instant scheduledFor, CycleKind kind, Instant now) {
        Optional<Digest> existing = digestRepository.findByTeamIdAndScheduledFor(teamId, scheduledFor);
        if (existing.isPresent()) {
            return new CycleClaim(existing.get().getId(), existing.get().getStatus(), false);
        }
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("팀을 찾을 수 없어요: teamId=" + teamId));
        try {
            Digest created = digestRepository.saveAndFlush(new Digest(team, scheduledFor, kind, now));
            return new CycleClaim(created.getId(), DigestStatus.PREPARING, true);
        } catch (DataIntegrityViolationException e) {
            // 다른 쪽이 같은 회차를 먼저 만들었다
            Digest raced = digestRepository.findByTeamIdAndScheduledFor(teamId, scheduledFor).orElseThrow(() -> e);
            return new CycleClaim(raced.getId(), raced.getStatus(), false);
        }
    }

    @Transactional(readOnly = true)
    public Optional<DigestStatus> findStatus(Long teamId, Instant scheduledFor) {
        return digestRepository.findByTeamIdAndScheduledFor(teamId, scheduledFor).map(Digest::getStatus);
    }

    /**
     * PREPARING 회차를 READY로 완성한다.
     * <ul>
     *     <li>summary가 성공이고 요약한 배치를 모두 담을 수 있으면 LLM.</li>
     *     <li>그 밖에는 지금 COLLECTED인 배치로 FALLBACK, 배치가 없으면 NO_CHANGE.</li>
     * </ul>
     * 이미 READY/SENT/FAILED면(마감 fallback이나 발송 시점 즉석 fallback이 먼저 처리했으면) 아무것도 쓰지 않고 empty.
     *
     * @param summary        null이면 LLM 없이 만든다 (마감 초과, 발송 시점 즉석 fallback)
     * @param fallbackReason summary가 null일 때 FALLBACK 사유
     */
    @Transactional
    public Optional<CompletedDigest> complete(Long digestId, SummaryResult summary, String fallbackReason, Instant now) {
        if (digestRepository.markReadyIfPreparing(digestId, DigestStatus.PREPARING, DigestStatus.READY, now) == 0) {
            return Optional.empty();
        }
        // 위 UPDATE가 행을 잠갔으므로 이 트랜잭션이 끝날 때까지 다른 쪽은 이 회차를 바꿀 수 없다
        Digest digestRef = digestRepository.getReferenceById(digestId);
        Long teamId = digestRepository.findById(digestId).orElseThrow().getTeam().getId();

        boolean llm = false;
        String reason = fallbackReason;
        if (summary != null && summary.succeeded()) {
            int claimed = changeBatchRepository.claimByIds(summary.changeBatchIds(), digestRef,
                    ChangeBatchStatus.COLLECTED, ChangeBatchStatus.PREPARED);
            llm = claimed == summary.changeBatchIds().size();
            if (!llm) {
                log.warn("요약하는 동안 배치 상태가 바뀌어 변경 목록으로 만들어요: digestId={}, 요약 배치 {}개 중 {}개만 남음",
                        digestId, summary.changeBatchIds().size(), claimed);
                reason = BATCHES_CHANGED_REASON;
            }
        } else if (summary != null) {
            reason = summary.hasChanges() ? summary.fallbackReason() : LATE_COLLECTED_REASON;
        }
        if (!llm) {
            changeBatchRepository.claimAllOfTeam(teamId, digestRef, ChangeBatchStatus.COLLECTED, ChangeBatchStatus.PREPARED);
        }

        Digest digest = digestRepository.findById(digestId).orElseThrow();
        Team team = digest.getTeam();
        List<ChangeBatch> batches = changeBatchRepository.findAllByDigestIdOrderByIdAsc(digestId);

        if (llm) {
            saveLlmItems(digest, batches, summary.items());
            digest.fillContent(DigestType.LLM, summaryFormatter.header(team.getName(), team.getPmDiscordUserId()),
                    team.getPmDiscordUserId(), null);
            return Optional.of(new CompletedDigest(DigestType.LLM, null));
        }
        if (batches.isEmpty()) {
            digest.fillContent(DigestType.NO_CHANGE, formatter.noChange(team.getName()), null, null);
            return Optional.of(new CompletedDigest(DigestType.NO_CHANGE, null));
        }
        saveFallbackItems(digest, batches);
        digest.fillContent(DigestType.FALLBACK, formatter.header(team.getName(), team.getPmDiscordUserId()),
                team.getPmDiscordUserId(), reason);
        return Optional.of(new CompletedDigest(DigestType.FALLBACK, reason));
    }

    /**
     * READY이고 아직 아무도 보내기 시작하지 않았을 때만 true.
     */
    public boolean claimSend(Long digestId, Instant now) {
        return digestRepository.claimSend(digestId, DigestStatus.READY, now) == 1;
    }

    public void releaseSendClaim(Long digestId) {
        digestRepository.releaseSendClaim(digestId, DigestStatus.READY);
    }

    @Transactional(readOnly = true)
    public SendableDigest loadForSend(Long digestId) {
        Digest digest = findDigest(digestId);
        Team team = digest.getTeam();
        List<SendableDigest.Item> items = digestItemRepository.findAllByDigestIdOrderByIdAsc(digestId).stream()
                .map(item -> new SendableDigest.Item(item.getId(), item.getText()))
                .toList();
        return new SendableDigest(digest.getId(), team.getId(), team.getName(), team.getDiscordChannelId(),
                digest.getScheduledFor(), digest.getCycleKind(), digest.getType(), digest.getHeaderText(),
                digest.getMentionUserId(), digest.getFallbackReason(), items);
    }

    /**
     * 발송 성공: 회차 SENT, 담긴 배치 모두 SENT.
     */
    @Transactional
    public void markSent(Long digestId, Map<Long, Long> messageIdByItemId, Instant sentAt) {
        Digest digest = findDigest(digestId);
        markDelivered(digestId, messageIdByItemId);
        changeBatchRepository.findAllByDigestIdOrderByIdAsc(digestId).forEach(ChangeBatch::markSent);
        digest.markSent(sentAt);
    }

    /**
     * 발송 실패: 회차 FAILED.
     * FALLBACK은 전달된 항목의 배치만 SENT, 나머지는 COLLECTED로 되돌린다.
     * LLM은 항목과 배치가 1:1이 아니라서 일부만 전달됐어도 모두 COLLECTED로 되돌린다.
     */
    @Transactional
    public void markFailed(Long digestId, Map<Long, Long> deliveredMessageIdByItemId, String reason) {
        Digest digest = findDigest(digestId);
        markDelivered(digestId, deliveredMessageIdByItemId);
        for (ChangeBatch batch : changeBatchRepository.findAllByDigestIdOrderByIdAsc(digestId)) {
            boolean delivered = digest.getType() == DigestType.FALLBACK
                    && batch.getDigestItem() != null
                    && deliveredMessageIdByItemId.containsKey(batch.getDigestItem().getId());
            if (delivered) {
                batch.markSent();
            } else {
                batch.revertToCollected();
            }
        }
        digest.markFailed(reason);
    }

    /**
     * 준비 취소 (재시작 복구 포기 등): 끝나지 않은 회차를 FAILED로 닫고 담긴 배치를 모두 COLLECTED로 되돌린다.
     *
     * @return 이미 끝난 회차였으면 false
     */
    @Transactional
    public boolean abandon(Long digestId, String reason) {
        Digest digest = findDigest(digestId);
        if (digest.isFinished()) {
            return false;
        }
        changeBatchRepository.findAllByDigestIdOrderByIdAsc(digestId).forEach(ChangeBatch::revertToCollected);
        digest.markFailed(reason);
        return true;
    }

    private void markDelivered(Long digestId, Map<Long, Long> messageIdByItemId) {
        for (DigestItem item : digestItemRepository.findAllByDigestIdOrderByIdAsc(digestId)) {
            Long messageId = messageIdByItemId.get(item.getId());
            if (messageId != null) {
                item.markDelivered(messageId);
            }
        }
    }

    private void saveLlmItems(Digest digest, List<ChangeBatch> batches, List<SummaryItem> summaryItems) {
        Map<String, TrackedRepo> repoBySha = new HashMap<>();
        for (ChangeBatch batch : batches) {
            batch.getCommits().forEach(commit -> repoBySha.put(commit.sha(), batch.getTrackedRepo()));
        }
        for (SummaryItem summaryItem : summaryItems) {
            Set<TrackedRepo> repos = new HashSet<>();
            summaryItem.commitShas().forEach(sha -> repos.add(repoBySha.get(sha)));
            TrackedRepo repo = repos.size() == 1 ? repos.iterator().next() : null;
            digestItemRepository.save(new DigestItem(digest, repo, summaryFormatter.itemText(summaryItem),
                    summaryItem.commitShas()));
        }
    }

    private void saveFallbackItems(Digest digest, List<ChangeBatch> batches) {
        Map<Long, List<ChangeBatch>> byRepo = new LinkedHashMap<>();
        for (ChangeBatch batch : batches) {
            byRepo.computeIfAbsent(batch.getTrackedRepo().getId(), id -> new ArrayList<>()).add(batch);
        }
        for (List<ChangeBatch> repoBatches : byRepo.values()) {
            TrackedRepo repo = repoBatches.getFirst().getTrackedRepo();
            List<String> commitShas = repoBatches.stream()
                    .flatMap(batch -> batch.getCommits().stream())
                    .map(CommitSnapshot::sha)
                    .toList();
            DigestItem item = digestItemRepository.save(new DigestItem(digest, repo,
                    formatter.itemText(repo.getFullName(), repoBatches), commitShas));
            repoBatches.forEach(batch -> batch.linkItem(item));
        }
    }

    private Digest findDigest(Long digestId) {
        return digestRepository.findById(digestId)
                .orElseThrow(() -> new IllegalStateException("발송 기록을 찾을 수 없어요: " + digestId));
    }
}
