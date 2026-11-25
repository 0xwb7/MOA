package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.domain.team.service.TeamLocks;
import discord.moa.discordmoa.global.config.ScheduleProperties;
import discord.moa.discordmoa.global.config.SchedulingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 발송 단계와 수동 발송(/발송테스트), /회차상태.
 * 발송 단계는 READY를 보내기만 하고, READY가 없으면 LLM 없이 즉석 fallback을 만들어 반드시 보낸다.
 */
@Slf4j
@Service
public class DigestService {

    static final String INLINE_NO_DIGEST_REASON = "준비된 회차가 없어 발송 시점에 변경 목록으로 만들었어요";
    static final String INLINE_PREPARING_REASON = "준비가 끝나지 않아 발송 시점에 변경 목록으로 만들었어요";
    static final String RESEND_WARNING = "발송을 시작한 뒤 서버가 멈춘 회차를 다시 보내요. "
            + "멈추기 전에 메시지가 이미 전송됐다면 PM이 같은 메시지를 두 번 받을 수 있어요 (중복 발송 가능)";
    static final String NO_REPO_MESSAGE = "등록된 레포가 없어요. /레포등록 으로 먼저 등록해 주세요.";

    private final DigestWriter digestWriter;
    private final DigestPreparer digestPreparer;
    private final DigestMessagePlanner planner;
    private final DigestSender digestSender;
    private final DigestRepository digestRepository;
    private final DigestItemRepository digestItemRepository;
    private final TeamRepository teamRepository;
    private final TrackedRepoRepository trackedRepoRepository;
    private final TeamLocks teamLocks;
    private final OpsNotifier opsNotifier;
    private final CycleCalendar calendar;
    private final ScheduleProperties scheduleProperties;
    private final ExecutorService sendExecutor;
    private final Clock clock;

    public DigestService(DigestWriter digestWriter, DigestPreparer digestPreparer, DigestMessagePlanner planner,
                         DigestSender digestSender, DigestRepository digestRepository,
                         DigestItemRepository digestItemRepository, TeamRepository teamRepository,
                         TrackedRepoRepository trackedRepoRepository, TeamLocks teamLocks, OpsNotifier opsNotifier, CycleCalendar calendar,
                         ScheduleProperties scheduleProperties,
                         @Qualifier(SchedulingConfig.SEND_EXECUTOR) ExecutorService sendExecutor,
                         Clock clock) {
        this.digestWriter = digestWriter;
        this.digestPreparer = digestPreparer;
        this.planner = planner;
        this.digestSender = digestSender;
        this.digestRepository = digestRepository;
        this.digestItemRepository = digestItemRepository;
        this.teamRepository = teamRepository;
        this.trackedRepoRepository = trackedRepoRepository;
        this.teamLocks = teamLocks;
        this.opsNotifier = opsNotifier;
        this.calendar = calendar;
        this.scheduleProperties = scheduleProperties;
        this.sendExecutor = sendExecutor;
        this.clock = clock;
    }

    /**
     * 발송 단계: 모든 팀을 발송 실행기에서 병렬로 보낸다 (한 팀이 락을 기다려도 다른 팀이 밀리지 않게).
     */
    public void sendCycle(Instant cycle) {
        // 활성 레포가 있는 팀 + 이 회차가 이미 준비된 팀 (준비 뒤 마지막 레포를 해제해도 그 회차는 보낸다)
        List<Team> teams = teamRepository.findAllForSendCycle(cycle);
        log.info("회차 발송 시작: 회차={}, 팀 {}개", cycle, teams.size());
        for (Team team : teams) {
            sendExecutor.execute(() -> {
                try {
                    sendScheduled(team, cycle);
                } catch (RuntimeException e) {
                    log.error("회차 발송 중 오류: team={}, 회차={}", team.getName(), cycle, e);
                    opsNotifier.notify(new OpsEvent(OpsEvent.Type.SEND_FAILED, team.getName(), cycle,
                            "발송 중 예상하지 못한 오류: " + e));
                }
            });
        }
    }

    /**
     * 팀 하나의 정기 회차를 보낸다.
     * 락은 최대 send-lock-wait만큼 기다리고, 못 잡아도 진행한다 (정확성은 DB 조건부 갱신이 보장).
     *
     * @return 이미 보냈거나 다른 쪽이 보내는 중이면 empty
     */
    public Optional<DigestSendResult> sendScheduled(Team team, Instant cycle) {
        boolean locked = teamLocks.tryLock(team.getId(), scheduleProperties.sendLockWait());
        if (!locked) {
            log.warn("팀 락을 {} 안에 잡지 못해 락 없이 발송을 진행해요: team={}, 회차={}",
                    scheduleProperties.sendLockWait(), team.getName(), cycle);
        }
        try {
            CycleClaim claim = digestWriter.claimCycle(team.getId(), cycle, CycleKind.SCHEDULED, clock.instant());
            if (claim.status() == DigestStatus.SENT || claim.status() == DigestStatus.FAILED) {
                log.info("이미 끝난 회차라 보내지 않아요: team={}, 회차={}, 상태={}", team.getName(), cycle, claim.status());
                return Optional.empty();
            }
            if (claim.isPreparing()) {
                String reason = claim.created() ? INLINE_NO_DIGEST_REASON : INLINE_PREPARING_REASON;
                digestWriter.complete(claim.digestId(), null, reason, clock.instant())
                        .ifPresent(saved -> opsNotifier.notify(new OpsEvent(OpsEvent.Type.SEND_INLINE_FALLBACK,
                                team.getName(), cycle, reason + " (유형 " + saved.type() + ")")));
            }
            return deliver(claim.digestId());
        } finally {
            if (locked) {
                teamLocks.unlock(team.getId());
            }
        }
    }

    /**
     * 재시작 복구용: 발송 도중 멈춰 선점만 남은 READY를 풀고 다시 보낸다.
     * 전송은 됐는데 SENT를 기록하기 전에 멈췄을 수도 있어서 PM이 같은 메시지를 두 번 받을 수 있다 (최소 한 번 발송, ADR-003).
     * 그래서 선점을 풀고 다시 보낼 때는 운영진에게 알린다.
     */
    public Optional<DigestSendResult> sendLate(Team team, Instant cycle) {
        digestRepository.findByTeamIdAndScheduledFor(team.getId(), cycle)
                .filter(digest -> digest.getStatus() == DigestStatus.READY && digest.getSendStartedAt() != null)
                .filter(digest -> digestWriter.releaseSendClaim(digest.getId()))
                .ifPresent(digest -> opsNotifier.notify(new OpsEvent(OpsEvent.Type.RECOVERY_RESEND, team.getName(), cycle,
                        RESEND_WARNING)));
        return sendScheduled(team, cycle);
    }

    /**
     * /발송테스트: 정기 회차와 섞이지 않는 수동 회차(지금 시각)로 바로 준비하고 보낸다. 팀이 처리 중이면 거절.
     */
    public ManualSendResult sendNowByChannel(Long channelId) {
        Team team = teamByChannel(channelId);
        if (!trackedRepoRepository.existsByTeamIdAndActiveTrue(team.getId())) {
            throw new IllegalStateException(NO_REPO_MESSAGE);
        }
        return teamLocks.callIfFree(team.getId(), team.getName(), () -> {
            Instant cycle = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Preparation preparation = digestPreparer.prepareTeam(team, cycle, CycleKind.MANUAL)
                    .orElseThrow(() -> new IllegalStateException("같은 시각의 회차가 이미 있어요. 잠시 후 다시 시도해 주세요."));
            DigestSendResult sent = deliver(preparation.digestId())
                    .orElseThrow(() -> new IllegalStateException("발송을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."));
            return new ManualSendResult(new TeamCollectResult(team.getName(), preparation.collected()), sent);
        });
    }

    @Transactional(readOnly = true)
    public CycleStatus cycleStatusByChannel(Long channelId) {
        Team team = teamByChannel(channelId);
        Instant now = clock.instant();
        Instant next = calendar.nextSendAfter(now);
        DigestStatus nextStatus = digestRepository.findByTeamIdAndScheduledFor(team.getId(), next)
                .map(Digest::getStatus).orElse(null);
        CycleStatus.Result lastScheduled = last(team.getId(), CycleKind.SCHEDULED, now);
        CycleStatus.Result lastManual = last(team.getId(), CycleKind.MANUAL, now);
        boolean hasActiveRepo = trackedRepoRepository.existsByTeamIdAndActiveTrue(team.getId());
        return new CycleStatus(team.getName(), next, calendar.prepareDeadline(next), nextStatus, lastScheduled, lastManual,
                hasActiveRepo);
    }

    /**
     * READY 회차를 선점해 보낸다. 다른 쪽이 이미 보냈거나 보내는 중이면 empty.
     */
    Optional<DigestSendResult> deliver(Long digestId) {
        if (!digestWriter.claimSend(digestId, clock.instant())) {
            log.info("이미 발송 중이거나 끝난 회차라 보내지 않아요: digestId={}", digestId);
            return Optional.empty();
        }
        SendableDigest digest = digestWriter.loadForSend(digestId);

        List<DigestMessage> messages = List.of();
        List<Long> messageIds;
        try {
            messages = toMessages(digest);
            messageIds = digestSender.send(digest.channelId(), messages);
        } catch (DigestSendException e) {
            return Optional.of(fail(digest, messages, e.getSentMessageIds(), e.getMessage(), e));
        } catch (RuntimeException e) {
            return Optional.of(fail(digest, messages, List.of(), "예상하지 못한 오류: " + e.getMessage(), e));
        }

        try {
            digestWriter.markSent(digestId, messageIdByItemId(messages, messageIds), clock.instant());
        } catch (RuntimeException e) {
            log.error("발송은 됐지만 결과 기록에 실패했어요: team={}, digestId={}, messageIds={}",
                    digest.teamName(), digestId, messageIds, e);
            throw e;
        }
        log.info("발송 완료: team={}, 회차={}, 유형={}, 항목 {}개", digest.teamName(), digest.scheduledFor(),
                digest.type(), digest.items().size());
        return Optional.of(new DigestSendResult(digest.teamName(), digest.type(), true, digest.items().size(),
                messageIds.size(), null, digest.fallbackReason(), digest.uncollectedRepos()));
    }

    private DigestSendResult fail(SendableDigest digest, List<DigestMessage> messages, List<Long> sentMessageIds,
                                  String reason, Exception cause) {
        String failureReason = sentMessageIds.isEmpty()
                ? reason
                : reason + " (메시지 " + messages.size() + "개 중 " + sentMessageIds.size() + "개는 발송됨)";
        log.error("발송 실패: team={}, digestId={}, reason={}", digest.teamName(), digest.digestId(), failureReason, cause);

        digestWriter.markFailed(digest.digestId(), messageIdByItemId(messages, sentMessageIds), failureReason);
        opsNotifier.notify(new OpsEvent(OpsEvent.Type.SEND_FAILED, digest.teamName(), digest.scheduledFor(), failureReason));
        return new DigestSendResult(digest.teamName(), digest.type(), false, digest.items().size(),
                sentMessageIds.size(), failureReason, digest.fallbackReason(), digest.uncollectedRepos());
    }

    private List<DigestMessage> toMessages(SendableDigest digest) {
        if (digest.type() == DigestType.NO_CHANGE) {
            return List.of(new DigestMessage(null, List.of(DigestBlock.textOnly(digest.headerText()))));
        }
        List<DigestMessagePlanner.PlannedItem> items = digest.items().stream()
                .map(item -> new DigestMessagePlanner.PlannedItem(item.itemId(), item.text()))
                .toList();
        return planner.plan(digest.headerText(), digest.mentionUserId(), items, digest.footer());
    }

    private CycleStatus.Result last(Long teamId, CycleKind kind, Instant now) {
        return digestRepository
                .findFirstByTeamIdAndCycleKindAndScheduledForLessThanEqualOrderByScheduledForDesc(teamId, kind, now)
                .map(digest -> CycleStatus.Result.of(digest,
                        digestItemRepository.countByDigestIdAndDiscordMessageIdIsNotNull(digest.getId()),
                        digestItemRepository.countByDigestIdAndDiscordMessageIdIsNotNullAndConfirmedAtIsNotNull(digest.getId())))
                .orElse(null);
    }

    private Team teamByChannel(Long channelId) {
        return teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
    }

    private static Map<Long, Long> messageIdByItemId(List<DigestMessage> messages, List<Long> messageIds) {
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < messageIds.size() && i < messages.size(); i++) {
            Long messageId = messageIds.get(i);
            messages.get(i).blocks().stream()
                    .filter(DigestBlock::hasButtons)
                    .forEach(block -> result.put(block.itemId(), messageId));
        }
        return result;
    }
}
