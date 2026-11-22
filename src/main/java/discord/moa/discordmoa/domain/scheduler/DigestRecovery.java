package discord.moa.discordmoa.domain.scheduler;

import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.domain.digest.service.DigestWriter;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.global.config.ScheduleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서버 시작 시 복구. 발송 시각이 지났는데 SENT가 아닌 정기 회차를
 * recovery-window(30분) 안이면 늦게라도 보내고, 넘었으면 FAILED로 닫고 배치를 COLLECTED로 돌린다.
 */
@Slf4j
@Component
public class DigestRecovery {

    static final String ABANDON_REASON = "서버가 멈춰 있는 동안 발송 시각이 %d분 넘게 지나 이 회차는 보내지 않았어요. 변경은 다음 회차에 포함돼요.";
    static final String MANUAL_ABANDON_REASON = "서버 재시작으로 중단된 수동 발송이에요. 변경은 다음 회차에 포함돼요.";

    private final DigestRepository digestRepository;
    private final DigestWriter digestWriter;
    private final DigestService digestService;
    private final TeamRepository teamRepository;
    private final OpsNotifier opsNotifier;
    private final CycleCalendar calendar;
    private final Duration window;
    private final Clock clock;

    public DigestRecovery(DigestRepository digestRepository, DigestWriter digestWriter, DigestService digestService,
                          TeamRepository teamRepository, OpsNotifier opsNotifier, CycleCalendar calendar,
                          ScheduleProperties properties, Clock clock) {
        this.digestRepository = digestRepository;
        this.digestWriter = digestWriter;
        this.digestService = digestService;
        this.teamRepository = teamRepository;
        this.opsNotifier = opsNotifier;
        this.calendar = calendar;
        this.window = properties.recoveryWindow();
        this.clock = clock;
    }

    public void recover() {
        Instant now = clock.instant();
        Set<String> handled = new HashSet<>();

        // 1) 발송 시각이 지났는데 끝나지 않은 회차 (준비 중·발송 전에 서버가 멈춤)
        List<Digest> overdue = digestRepository.findAllByStatusInAndScheduledForLessThanEqualOrderByScheduledForAsc(
                List.of(DigestStatus.PREPARING, DigestStatus.READY), now);
        for (Digest digest : overdue) {
            Team team = digest.getTeam();
            handled.add(key(team.getId(), digest.getScheduledFor()));
            if (digest.getCycleKind() == CycleKind.MANUAL) {
                digestWriter.abandon(digest.getId(), MANUAL_ABANDON_REASON);
                continue;
            }
            Duration late = Duration.between(digest.getScheduledFor(), now);
            if (late.compareTo(window) <= 0) {
                sendLate(team, digest.getScheduledFor(), late);
            } else if (digestWriter.abandon(digest.getId(), ABANDON_REASON.formatted(window.toMinutes()))) {
                opsNotifier.notify(new OpsEvent(OpsEvent.Type.RECOVERY_ABANDONED, team.getName(), digest.getScheduledFor(),
                        "발송 시각보다 " + late.toMinutes() + "분 늦어 포기했어요. 변경은 다음 회차에 포함돼요."));
            }
        }

        // 2) 준비·발송 시각 내내 서버가 꺼져 있어 회차 기록조차 없는 경우
        calendar.latestSendAtOrBefore(now, window).ifPresent(cycle -> {
            for (Team team : teamRepository.findAllWithTrackedRepo()) {
                if (handled.contains(key(team.getId(), cycle))
                        || digestRepository.findByTeamIdAndScheduledFor(team.getId(), cycle).isPresent()) {
                    continue;
                }
                sendLate(team, cycle, Duration.between(cycle, now));
            }
        });
    }

    private void sendLate(Team team, Instant cycle, Duration late) {
        log.warn("재시작 복구: 발송 시각보다 {}초 늦게 보내요: team={}, 회차={}", late.toSeconds(), team.getName(), cycle);
        opsNotifier.notify(new OpsEvent(OpsEvent.Type.RECOVERY_LATE_SEND, team.getName(), cycle,
                "발송 시각보다 " + late.toMinutes() + "분 늦게 보내요"));
        try {
            digestService.sendLate(team, cycle);
        } catch (RuntimeException e) {
            log.error("재시작 복구 발송 실패: team={}, 회차={}", team.getName(), cycle, e);
            opsNotifier.notify(new OpsEvent(OpsEvent.Type.SEND_FAILED, team.getName(), cycle, "복구 발송 중 오류: " + e));
        }
    }

    private static String key(Long teamId, Instant cycle) {
        return teamId + "@" + cycle;
    }
}
