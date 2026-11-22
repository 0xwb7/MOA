package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.scheduler.CycleCalendar;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.service.TeamLocks;
import discord.moa.discordmoa.global.config.SchedulingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 준비 단계: 수집 → 요약 → READY 저장. 발송은 하지 않는다.
 */
@Slf4j
@Service
public class DigestPreparer {

    static final String DEADLINE_REASON = "준비 마감까지 요약이 끝나지 않아 변경 목록으로 보내요";

    private final ChangeCollector changeCollector;
    private final ChangeSummarizer changeSummarizer;
    private final DigestWriter digestWriter;
    private final TeamRepository teamRepository;
    private final TeamLocks teamLocks;
    private final OpsNotifier opsNotifier;
    private final CycleCalendar calendar;
    private final ExecutorService prepareExecutor;
    private final Clock clock;

    public DigestPreparer(ChangeCollector changeCollector, ChangeSummarizer changeSummarizer, DigestWriter digestWriter,
                          TeamRepository teamRepository, TeamLocks teamLocks, OpsNotifier opsNotifier,
                          CycleCalendar calendar,
                          @Qualifier(SchedulingConfig.PREPARE_EXECUTOR) ExecutorService prepareExecutor,
                          Clock clock) {
        this.changeCollector = changeCollector;
        this.changeSummarizer = changeSummarizer;
        this.digestWriter = digestWriter;
        this.teamRepository = teamRepository;
        this.teamLocks = teamLocks;
        this.opsNotifier = opsNotifier;
        this.calendar = calendar;
        this.prepareExecutor = prepareExecutor;
        this.clock = clock;
    }

    /**
     * 정기 회차 준비. 모든 팀을 준비 실행기(동시 최대 N팀)에서 돌리고, 마감까지 기다린다.
     * 마감까지 끝나지 않은 팀은 작업을 취소하고 변경 목록(FALLBACK)으로 READY를 만든다.
     * 한 팀의 실패는 다른 팀에 영향을 주지 않는다. 코디네이터 스레드에서 호출한다 (스케줄러 스레드 X).
     */
    public void prepareCycle(Instant cycle) {
        Instant deadline = calendar.prepareDeadline(cycle);
        if (!clock.instant().isBefore(deadline)) {
            log.warn("준비 마감({})이 이미 지나 준비하지 않아요. 발송 시점에 변경 목록으로 보내요: 회차={}", deadline, cycle);
            return;
        }

        Map<Team, Future<?>> tasks = new LinkedHashMap<>();
        for (Team team : teamRepository.findAllWithTrackedRepo()) {
            tasks.put(team, prepareExecutor.submit(() -> prepareScheduledTeam(team, cycle)));
        }
        log.info("회차 준비 시작: 회차={}, 팀 {}개, 마감={}", cycle, tasks.size(), deadline);

        for (Map.Entry<Team, Future<?>> task : tasks.entrySet()) {
            Team team = task.getKey();
            long waitMillis = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
            try {
                task.getValue().get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                task.getValue().cancel(true);
                fallbackAtDeadline(team, cycle);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                log.error("팀 준비 실패: team={}, 회차={}", team.getName(), cycle, cause);
                opsNotifier.notify(new OpsEvent(OpsEvent.Type.PREPARE_FAILED, team.getName(), cycle,
                        "준비 중 오류가 났어요 (발송 시점에 변경 목록으로 보내요): " + cause));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("준비 코디네이터가 중단됐어요 (서버 종료 등): 회차={}", cycle);
                return;
            }
        }
        log.info("회차 준비 끝: 회차={}", cycle);
    }

    /**
     * 팀 하나를 준비한다. 호출하는 쪽이 팀 락을 잡고 있어야 한다.
     *
     * @return 회차가 이미 처리됐으면(READY/SENT/FAILED) empty
     */
    public Optional<Preparation> prepareTeam(Long teamId, Instant cycle, CycleKind kind) {
        CycleClaim claim = digestWriter.claimCycle(teamId, cycle, kind, clock.instant());
        if (!claim.isPreparing()) {
            log.info("이미 처리된 회차라 준비하지 않아요: teamId={}, 회차={}, 상태={}", teamId, cycle, claim.status());
            return Optional.empty();
        }

        try {
            List<RepoCollectResult> collected = changeCollector.collectTeam(teamId);
            SummaryResult summary = changeSummarizer.summarize(teamId, LlmCallPurpose.DIGEST);
            Optional<DigestType> saved = digestWriter.complete(claim.digestId(), summary, null, clock.instant());
            if (saved.isEmpty()) {
                log.info("준비가 끝났지만 이미 처리된 회차라 결과를 버려요: teamId={}, 회차={}", teamId, cycle);
            }
            return Optional.of(new Preparation(claim.digestId(), collected, saved));
        } catch (RuntimeException e) {
            if (kind == CycleKind.MANUAL) {
                // 수동 회차는 발송 시점 fallback이 없으니 바로 닫고 배치를 되돌린다
                digestWriter.abandon(claim.digestId(), "준비 중 오류: " + e.getMessage());
            }
            throw e;
        }
    }

    private void prepareScheduledTeam(Team team, Instant cycle) {
        if (!teamLocks.tryLock(team.getId())) {
            opsNotifier.notify(new OpsEvent(OpsEvent.Type.PREPARE_FAILED, team.getName(), cycle,
                    "다른 작업(/발송테스트, /수집테스트) 중이라 준비하지 못했어요. 발송 시점에 변경 목록으로 보내요"));
            return;
        }
        try {
            prepareTeam(team.getId(), cycle, CycleKind.SCHEDULED);
        } finally {
            teamLocks.unlock(team.getId());
        }
    }

    private void fallbackAtDeadline(Team team, Instant cycle) {
        try {
            CycleClaim claim = digestWriter.claimCycle(team.getId(), cycle, CycleKind.SCHEDULED, clock.instant());
            if (!claim.isPreparing()) {
                return;   // 마감 직전에 끝났다
            }
            digestWriter.complete(claim.digestId(), null, DEADLINE_REASON, clock.instant())
                    .ifPresent(type -> opsNotifier.notify(new OpsEvent(OpsEvent.Type.PREPARE_DEADLINE_FALLBACK,
                            team.getName(), cycle, DEADLINE_REASON + " (유형 " + type + ")")));
        } catch (RuntimeException e) {
            log.error("마감 fallback 저장 실패: team={}, 회차={}", team.getName(), cycle, e);
            opsNotifier.notify(new OpsEvent(OpsEvent.Type.PREPARE_FAILED, team.getName(), cycle,
                    "마감 fallback 저장 실패 (발송 시점에 다시 시도해요): " + e));
        }
    }
}
