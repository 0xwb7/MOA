package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.scheduler.CycleCalendar;
import discord.moa.discordmoa.domain.scheduler.DigestRecovery;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.domain.team.service.TeamBusyException;
import discord.moa.discordmoa.domain.team.service.TeamLocks;
import discord.moa.discordmoa.support.TeamLockHolder;
import discord.moa.discordmoa.global.config.ScheduleProperties;
import discord.moa.discordmoa.support.DirectExecutorService;
import discord.moa.discordmoa.support.H2Constraints;
import discord.moa.discordmoa.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 준비 → 발송 회차 흐름을 실제 JPA(H2)로 검증한다.
 * 유일 제약, 조건부 UPDATE처럼 DB가 보장하는 부분이 핵심이라 리포지토리를 mock으로 두지 않는다.
 * 외부 경계(GitHub 수집, LLM 요약, Discord 발송, 운영 알림)만 mock이고, 시각은 MutableClock으로 옮긴다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스가 스스로 트랜잭션을 나누는 동작을 그대로 검증
@Import({DigestWriter.class, FallbackDigestFormatter.class, SummaryDigestFormatter.class})
class DigestCycleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant CYCLE = kst(9, 0);             // 2026-11-27 09:00 KST
    private static final Instant NEXT_CYCLE = kst(21, 0);
    private static final Long CHANNEL_ID = 100L;
    private static final Long PM_ID = 7L;

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TrackedRepoRepository trackedRepoRepository;
    @Autowired
    private ChangeBatchRepository changeBatchRepository;
    @Autowired
    private DigestRepository digestRepository;
    @Autowired
    private DigestItemRepository digestItemRepository;
    @Autowired
    private DigestWriter digestWriter;
    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private final ChangeCollector changeCollector = mock(ChangeCollector.class);
    private final ChangeSummarizer changeSummarizer = mock(ChangeSummarizer.class);
    private final DigestSender digestSender = mock(DigestSender.class);
    private final OpsNotifier opsNotifier = mock(OpsNotifier.class);
    private final MutableClock clock = new MutableClock(kst(8, 30), SEOUL);
    private final TeamLocks teamLocks = new TeamLocks();

    private ScheduleProperties properties;
    private CycleCalendar calendar;
    private DigestPreparer preparer;
    private DigestService service;
    private DigestRecovery recovery;

    private Team team;
    private TrackedRepo backend;
    private TrackedRepo frontend;
    private int shaSeq;

    @BeforeEach
    void setUp() {
        H2Constraints.dropChecks(entityManagerFactory);
        team = teamRepository.save(new Team("1팀", CHANNEL_ID, PM_ID));
        backend = trackedRepoRepository.save(new TrackedRepo(team, 1L, "team1/backend", "main", null, 555L));
        frontend = trackedRepoRepository.save(new TrackedRepo(team, 2L, "team1/frontend", "main", null, 555L));
        given(changeCollector.collectTeam(anyLong())).willReturn(List.of());
        givenSummaryFallback();
        given(digestSender.send(anyLong(), anyList())).willAnswer(inv -> {
            List<DigestMessage> messages = inv.getArgument(1);
            return IntStream.range(0, messages.size()).mapToObj(i -> 9001L + i).toList();
        });
        build(Duration.ofSeconds(2), new DirectExecutorService());
    }

    @AfterEach
    void tearDown() {
        changeBatchRepository.deleteAllInBatch();
        digestItemRepository.deleteAllInBatch();
        digestRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
    }

    // ---- 정상 흐름 ----

    @Test
    void 준비하면_READY로_저장되고_발송하면_SENT가_되며_배치도_SENT가_된다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        givenSummaryLlm(new SummaryStatus[]{SummaryStatus.PARTIAL}, "로그인 서버가 준비됐어요");

        preparer.prepareCycle(CYCLE);

        Digest ready = cycleDigest(CYCLE);
        assertThat(ready.getStatus()).isEqualTo(DigestStatus.READY);
        assertThat(ready.getType()).isEqualTo(DigestType.LLM);
        assertThat(ready.getCycleKind()).isEqualTo(CycleKind.SCHEDULED);
        assertThat(ready.getHeaderText()).startsWith("<@7>").contains("이런 변경이 있었어요");
        assertThat(ready.getMentionUserId()).isEqualTo(PM_ID);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.PREPARED);
        verify(digestSender, never()).send(anyLong(), anyList());   // 준비 단계는 보내지 않는다

        clock.set(CYCLE);
        DigestSendResult result = service.sendScheduled(team, CYCLE).orElseThrow();

        assertThat(result.sent()).isTrue();
        assertThat(result.type()).isEqualTo(DigestType.LLM);
        Digest sent = cycleDigest(CYCLE);
        assertThat(sent.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(sent.getSentAt()).isEqualTo(CYCLE);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(items(sent)).extracting(DigestItem::getText).containsExactly("🟡 로그인 서버가 준비됐어요");
        assertThat(items(sent)).extracting(DigestItem::getDiscordMessageId).containsExactly(9001L);

        DigestMessage message = sentMessages().getFirst();
        assertThat(message.mentionUserId()).isEqualTo(PM_ID);
        assertThat(message.blocks().getFirst().text()).isEqualTo(sent.getHeaderText());
        verify(opsNotifier, never()).notify(any());
    }

    @Test
    void 준비_후에_수집된_배치는_이번_회차에_들어가지_않고_다음_회차에_들어간다() {
        ChangeBatch before = collected(backend, "8:30 전에 들어온 커밋");
        preparer.prepareCycle(CYCLE);

        clock.set(kst(8, 40));
        ChangeBatch after = collected(frontend, "8:40에 들어온 커밋");

        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        assertThat(batch(before).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(batch(after).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(items(cycleDigest(CYCLE))).extracting(DigestItem::getText)
                .singleElement().asString().contains("8:30 전에 들어온 커밋").doesNotContain("8:40");

        clock.set(kst(20, 30));
        preparer.prepareCycle(NEXT_CYCLE);
        clock.set(NEXT_CYCLE);
        service.sendScheduled(team, NEXT_CYCLE);

        assertThat(batch(after).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(items(cycleDigest(NEXT_CYCLE))).extracting(DigestItem::getText)
                .singleElement().asString().contains("8:40에 들어온 커밋");
    }

    @Test
    void 같은_회차를_두_번_준비하고_두_번_발송해도_한_번만_처리한다() {
        collected(backend, "로그인 API 추가");

        preparer.prepareCycle(CYCLE);
        preparer.prepareCycle(CYCLE);
        assertThat(preparer.prepareTeam(team, CYCLE, CycleKind.SCHEDULED)).isEmpty();

        verify(changeSummarizer, times(1)).summarize(team.getId(), LlmCallPurpose.DIGEST);
        assertThat(digestRepository.findAll()).hasSize(1);

        clock.set(CYCLE);
        assertThat(service.sendScheduled(team, CYCLE)).isPresent();
        assertThat(service.sendScheduled(team, CYCLE)).isEmpty();
        verify(digestSender, times(1)).send(anyLong(), anyList());
    }

    @Test
    void 변경이_없으면_멘션_없는_NO_CHANGE를_READY로_준비해_보낸다() {
        preparer.prepareCycle(CYCLE);

        Digest ready = cycleDigest(CYCLE);
        assertThat(ready.getType()).isEqualTo(DigestType.NO_CHANGE);
        assertThat(ready.getMentionUserId()).isNull();

        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        verify(opsNotifier, never()).notify(any());   // 변경 없음은 알림 대상이 아니다
        DigestMessage message = sentMessages().getFirst();
        assertThat(message.mentionUserId()).isNull();
        assertThat(message.blocks()).singleElement().satisfies(block -> {
            assertThat(block.text()).isEqualTo("지난 12시간 동안 '1팀'에 변경 사항이 없어요");
            assertThat(block.hasButtons()).isFalse();
        });
    }

    // ---- 반드시 발송: 발송 시점 즉석 fallback ----

    @Test
    void 준비가_실패하면_발송_시점에_LLM_없이_변경_목록으로_바로_보낸다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        given(changeSummarizer.summarize(anyLong(), any())).willThrow(new IllegalStateException("DB 연결 끊김"));

        preparer.prepareCycle(CYCLE);

        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.PREPARING);
        assertThat(opsEvents()).extracting(OpsEvent::type).containsExactly(OpsEvent.Type.PREPARE_FAILED);

        clock.set(CYCLE);
        DigestSendResult result = service.sendScheduled(team, CYCLE).orElseThrow();

        assertThat(result.sent()).isTrue();
        assertThat(result.type()).isEqualTo(DigestType.FALLBACK);
        assertThat(result.summaryFallbackReason()).isEqualTo(DigestService.INLINE_PREPARING_REASON);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(opsEvents()).extracting(OpsEvent::type)
                .containsExactly(OpsEvent.Type.PREPARE_FAILED, OpsEvent.Type.SEND_INLINE_FALLBACK);
        verify(changeSummarizer, times(1)).summarize(anyLong(), any());   // 발송 단계는 LLM을 부르지 않는다
    }

    @Test
    void 준비_기록이_아예_없어도_발송_시점에_만들어_보낸다() {
        collected(backend, "로그인 API 추가");
        clock.set(CYCLE);

        DigestSendResult result = service.sendScheduled(team, CYCLE).orElseThrow();

        assertThat(result.type()).isEqualTo(DigestType.FALLBACK);
        assertThat(result.summaryFallbackReason()).isEqualTo(DigestService.INLINE_NO_DIGEST_REASON);
        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
        verify(changeSummarizer, never()).summarize(anyLong(), any());
    }

    @Test
    void 준비가_발송_뒤에_끝나면_결과를_저장하지_않고_버린다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        // LLM이 오래 걸리는 사이에 9시 발송이 먼저 즉석 fallback으로 보낸 상황
        given(changeSummarizer.summarize(anyLong(), any())).willAnswer(inv -> {
            List<Long> ids = collectedIds();
            clock.set(CYCLE);
            service.sendScheduled(team, CYCLE);
            return SummaryResult.success(ids,
                    List.of(new SummaryItem(SummaryStatus.READY, "늦게 끝난 LLM 요약", List.of(sha(login)))), null);
        });

        Preparation preparation = preparer.prepareTeam(team, CYCLE, CycleKind.SCHEDULED).orElseThrow();

        assertThat(preparation.saved()).isEmpty();
        Digest digest = cycleDigest(CYCLE);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(digest.getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(items(digest)).extracting(DigestItem::getText).noneMatch(text -> text.contains("늦게 끝난"));
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        verify(digestSender, times(1)).send(anyLong(), anyList());
    }

    @Test
    void 마감까지_요약이_안_끝난_팀은_변경_목록으로_READY를_만들고_늦게_끝난_요약은_버린다() throws Exception {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        CountDownLatch summarizing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(changeSummarizer.summarize(anyLong(), any())).willAnswer(inv -> {
            List<Long> ids = collectedIds();
            summarizing.countDown();
            awaitIgnoringInterrupt(release);   // 취소(interrupt)돼도 끝까지 가서 저장을 시도한다
            return SummaryResult.success(ids,
                    List.of(new SummaryItem(SummaryStatus.READY, "마감 뒤에 끝난 요약", List.of(sha(login)))), null);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        // 작업을 넘기는 순간 시각을 마감(08:58)으로 옮겨, 코디네이터가 실제로 기다리지 않게 한다
        build(Duration.ofSeconds(2), new ClockAdvancingExecutor(worker, clock, kst(8, 58), summarizing));

        preparer.prepareCycle(CYCLE);

        Digest ready = cycleDigest(CYCLE);
        assertThat(ready.getStatus()).isEqualTo(DigestStatus.READY);
        assertThat(ready.getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(ready.getFallbackReason()).isEqualTo(DigestPreparer.DEADLINE_REASON);
        assertThat(opsEvents()).extracting(OpsEvent::type).containsExactly(OpsEvent.Type.PREPARE_DEADLINE_FALLBACK);

        release.countDown();
        worker.shutdown();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Digest after = cycleDigest(CYCLE);
        assertThat(after.getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(items(after)).extracting(DigestItem::getText).noneMatch(text -> text.contains("마감 뒤에"));
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.PREPARED);
    }

    @Test
    void 한_팀의_준비_실패가_다른_팀의_준비를_막지_않는다() {
        Team other = teamRepository.save(new Team("2팀", 200L, 8L));
        TrackedRepo otherRepo = trackedRepoRepository.save(new TrackedRepo(other, 3L, "team2/app", "main", null, 556L));
        collected(backend, "1팀 커밋");
        ChangeBatch otherBatch = collected(otherRepo, "2팀 커밋");
        given(changeCollector.collectTeam(team.getId())).willThrow(new IllegalStateException("GitHub 장애"));

        preparer.prepareCycle(CYCLE);

        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.PREPARING);
        Digest otherDigest = digestRepository.findByTeamIdAndScheduledFor(other.getId(), CYCLE).orElseThrow();
        assertThat(otherDigest.getStatus()).isEqualTo(DigestStatus.READY);
        assertThat(batch(otherBatch).getStatus()).isEqualTo(ChangeBatchStatus.PREPARED);
        assertThat(opsEvents()).filteredOn(event -> event.teamName().equals("1팀")).singleElement()
                .extracting(OpsEvent::type).isEqualTo(OpsEvent.Type.PREPARE_FAILED);
        assertThat(opsEvents()).filteredOn(event -> event.teamName().equals("2팀"))
                .extracting(OpsEvent::type).containsExactly(OpsEvent.Type.SUMMARY_FALLBACK);
    }

    // ---- 발송 실패 ----

    @Test
    void 발송에_실패하면_FAILED와_원인을_남기고_배치는_COLLECTED로_돌아가_다음_회차에_들어간다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);
        given(digestSender.send(anyLong(), anyList()))
                .willThrow(new DigestSendException("봇에게 #1팀 채널의 Send Messages 권한이 없어요.", List.of()));

        clock.set(CYCLE);
        DigestSendResult result = service.sendScheduled(team, CYCLE).orElseThrow();

        assertThat(result.sent()).isFalse();
        Digest failed = cycleDigest(CYCLE);
        assertThat(failed.getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("Send Messages");
        ChangeBatch reverted = batch(login);
        assertThat(reverted.getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(reverted.getDigest()).isNull();
        assertThat(opsEvents()).extracting(OpsEvent::type)
                .containsExactly(OpsEvent.Type.SUMMARY_FALLBACK, OpsEvent.Type.SEND_FAILED);

        willReturn(List.of(9100L)).given(digestSender).send(anyLong(), anyList());
        clock.set(kst(20, 30));
        preparer.prepareCycle(NEXT_CYCLE);
        clock.set(NEXT_CYCLE);
        service.sendScheduled(team, NEXT_CYCLE);

        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(items(cycleDigest(NEXT_CYCLE))).singleElement().extracting(DigestItem::getText)
                .asString().contains("로그인 API 추가");
    }

    @Test
    void 변경_목록이_일부_메시지만_발송되면_보낸_항목의_배치만_SENT로_남긴다() {
        List<ChangeBatch> batches = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {   // 레포 8개 → 메시지 2개 (7 + 1)
            TrackedRepo repo = trackedRepoRepository.save(new TrackedRepo(team, 100 + i, "team1/repo" + i, "main", null, 555L));
            batches.add(collected(repo, "커밋 " + i));
        }
        preparer.prepareCycle(CYCLE);
        given(digestSender.send(anyLong(), anyList())).willThrow(new DigestSendException("Discord 오류", List.of(9001L)));

        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        assertThat(cycleDigest(CYCLE).getFailureReason()).contains("메시지 2개 중 1개는 발송됨");
        assertThat(batches.subList(0, 7)).allSatisfy(b -> assertThat(batch(b).getStatus()).isEqualTo(ChangeBatchStatus.SENT));
        assertThat(batch(batches.get(7)).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
    }

    @Test
    void LLM_요약이_일부_메시지만_발송되면_배치를_모두_COLLECTED로_돌린다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        SummaryStatus[] statuses = new SummaryStatus[8];
        java.util.Arrays.fill(statuses, SummaryStatus.PARTIAL);
        givenSummaryLlm(statuses, "기능");
        preparer.prepareCycle(CYCLE);
        given(digestSender.send(anyLong(), anyList())).willThrow(new DigestSendException("Discord 오류", List.of(9001L)));

        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
    }

    // ---- 재시작 복구 ----

    @Test
    void 재시작했을_때_발송_시각에서_30분_이내면_늦게라도_보낸다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);   // READY까지 저장된 뒤 9시 전에 서버가 멈춤

        clock.set(kst(9, 20));
        recovery.recover();

        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        // 기본 요약 mock은 '요약 끔' fallback이라 준비 때 SUMMARY_FALLBACK이 먼저 나간다
        assertThat(opsEvents()).extracting(OpsEvent::type)
                .containsExactly(OpsEvent.Type.SUMMARY_FALLBACK, OpsEvent.Type.RECOVERY_LATE_SEND);
    }

    @Test
    void 준비_중에_서버가_멈췄으면_재시작_후_즉석_fallback으로_늦게_보낸다() {
        collected(backend, "로그인 API 추가");
        digestWriter.claimCycle(team.getId(), CYCLE, CycleKind.SCHEDULED, kst(8, 30));   // PREPARING에서 멈춤

        clock.set(kst(9, 10));
        recovery.recover();

        Digest digest = cycleDigest(CYCLE);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(digest.getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(opsEvents()).extracting(OpsEvent::type)
                .containsExactly(OpsEvent.Type.RECOVERY_LATE_SEND, OpsEvent.Type.SEND_INLINE_FALLBACK);
    }

    @Test
    void 발송_도중에_서버가_멈췄으면_재시작_후_다시_보낸다() {
        collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);
        Digest ready = cycleDigest(CYCLE);
        assertThat(digestWriter.claimSend(ready.getId(), CYCLE)).isTrue();   // 선점만 하고 멈춤

        clock.set(kst(9, 5));
        recovery.recover();

        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
        verify(digestSender, times(1)).send(anyLong(), anyList());
    }

    @Test
    void 준비와_발송_시각_내내_서버가_꺼져_있었으면_재시작_후_회차를_만들어_보낸다() {
        collected(backend, "로그인 API 추가");

        clock.set(kst(9, 25));
        recovery.recover();

        Digest digest = cycleDigest(CYCLE);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(digest.getType()).isEqualTo(DigestType.FALLBACK);
    }

    @Test
    void 발송_시각에서_30분이_넘었으면_FAILED로_닫고_배치를_COLLECTED로_돌린다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);

        clock.set(kst(9, 40));
        recovery.recover();

        Digest digest = cycleDigest(CYCLE);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(digest.getFailureReason()).contains("30분 넘게");
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(opsEvents()).extracting(OpsEvent::type)
                .containsExactly(OpsEvent.Type.SUMMARY_FALLBACK, OpsEvent.Type.RECOVERY_ABANDONED);
        verify(digestSender, never()).send(anyLong(), anyList());
    }

    @Test
    void 끝나지_않은_수동_회차는_재시작_후_다시_보내지_않고_닫는다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        Instant manualCycle = kst(10, 3);   // 정기 회차(09:00) 복구 범위(30분) 밖
        Long digestId = digestWriter.claimCycle(team.getId(), manualCycle, CycleKind.MANUAL, manualCycle).digestId();
        digestWriter.complete(digestId, null, "테스트", manualCycle);

        clock.set(kst(10, 4));
        recovery.recover();

        assertThat(digestRepository.findById(digestId).orElseThrow().getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        verify(digestSender, never()).send(anyLong(), anyList());
    }

    // ---- 운영 알림: 레포 수집 실패, LLM 요약 실패 ----

    @Test
    void 정기_준비에서_레포_수집이_실패하면_원인과_함께_알리고_발송은_그대로_한다() {
        collected(frontend, "로그인 화면");
        given(changeCollector.collectTeam(team.getId())).willReturn(List.of(
                new RepoCollectResult("team1/backend", RepoCollectResult.Outcome.FAILED, 0, 0, false, false,
                        "App 설치가 해제됐어요 (레포 주인이 다시 설치해야 해요)"),
                new RepoCollectResult("team1/frontend", RepoCollectResult.Outcome.NO_CHANGE, 0, 0, false, false, null)));

        preparer.prepareCycle(CYCLE);
        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        OpsEvent collectFailed = opsEvents().getFirst();
        assertThat(collectFailed.type()).isEqualTo(OpsEvent.Type.COLLECT_FAILED);
        assertThat(collectFailed.type().severity()).isEqualTo(OpsEvent.Severity.WARNING);
        assertThat(collectFailed.teamName()).isEqualTo("1팀");
        assertThat(collectFailed.scheduledFor()).isEqualTo(CYCLE);
        assertThat(collectFailed.detail())
                .contains("team1/backend: App 설치가 해제됐어요 (레포 주인이 다시 설치해야 해요)")
                .doesNotContain("team1/frontend");
        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
    }

    @Test
    void 수동_발송에서는_레포_수집_실패를_운영_알림으로_보내지_않는다() {
        given(changeCollector.collectTeam(team.getId())).willReturn(List.of(
                new RepoCollectResult("team1/backend", RepoCollectResult.Outcome.FAILED, 0, 0, false, false, "원인")));

        service.sendNowByChannel(CHANNEL_ID);

        verify(opsNotifier, never()).notify(any());
    }

    @Test
    void 정기_준비에서_LLM_요약이_실패해_변경_목록이_되면_사유와_함께_알린다() {
        collected(backend, "로그인 API 추가");
        given(changeSummarizer.summarize(anyLong(), any())).willAnswer(inv ->
                SummaryResult.fallback(collectedIds(), "응답 검증 실패: 입력에 없는 커밋 SHA가 응답에 있어요", null));

        preparer.prepareCycle(CYCLE);

        assertThat(cycleDigest(CYCLE).getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(opsEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(OpsEvent.Type.SUMMARY_FALLBACK);
            assertThat(event.type().severity()).isEqualTo(OpsEvent.Severity.WARNING);
            assertThat(event.detail()).isEqualTo("응답 검증 실패: 입력에 없는 커밋 SHA가 응답에 있어요");
        });
    }

    @Test
    void LLM_일일_상한에_걸려_변경_목록이_되어도_알린다() {
        collected(backend, "로그인 API 추가");
        given(changeSummarizer.summarize(anyLong(), any())).willAnswer(inv ->
                SummaryResult.limitReached(collectedIds(), "오늘 정기 발송용 LLM 호출 상한(60회)에 도달했어요", null));

        preparer.prepareCycle(CYCLE);

        assertThat(opsEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(OpsEvent.Type.SUMMARY_FALLBACK);
            assertThat(event.detail()).contains("LLM 호출 상한(60회)");
        });
    }

    @Test
    void LLM_요약이_성공하면_운영_알림이_없다() {
        collected(backend, "로그인 API 추가");
        givenSummaryLlm(new SummaryStatus[]{SummaryStatus.READY}, "로그인 기능을 써 볼 수 있어요");

        preparer.prepareCycle(CYCLE);

        assertThat(cycleDigest(CYCLE).getType()).isEqualTo(DigestType.LLM);
        verify(opsNotifier, never()).notify(any());
    }

    // ---- 레포가 없는 팀 ----

    @Test
    void 레포가_없는_팀은_준비와_발송_대상에서_빠진다() {
        Team empty = teamRepository.save(new Team("빈팀", 300L, 9L));
        collected(backend, "로그인 API 추가");

        preparer.prepareCycle(CYCLE);
        clock.set(CYCLE);
        service.sendCycle(CYCLE);

        assertThat(digestRepository.findByTeamIdAndScheduledFor(empty.getId(), CYCLE)).isEmpty();
        verify(digestSender, never()).send(eq(300L), anyList());
        // 레포가 있는 팀은 기존과 같다
        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
        verify(digestSender, times(1)).send(eq(CHANNEL_ID), anyList());
        assertThat(opsEvents()).extracting(OpsEvent::teamName).containsOnly("1팀");
    }

    @Test
    void 레포가_없는_팀은_재시작_복구에서도_회차를_만들지_않는다() {
        Team empty = teamRepository.save(new Team("빈팀", 300L, 9L));

        clock.set(kst(9, 25));   // 준비·발송 시각 내내 서버가 꺼져 있었던 상황
        recovery.recover();

        assertThat(digestRepository.findByTeamIdAndScheduledFor(empty.getId(), CYCLE)).isEmpty();
        verify(digestSender, never()).send(eq(300L), anyList());
        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
    }

    @Test
    void 준비_후에_첫_레포를_등록한_팀은_발송_시점에_즉석으로_만들어_보낸다() {
        Team newcomer = teamRepository.save(new Team("새팀", 300L, 9L));
        preparer.prepareCycle(CYCLE);
        assertThat(digestRepository.findByTeamIdAndScheduledFor(newcomer.getId(), CYCLE)).isEmpty();

        clock.set(kst(8, 40));
        trackedRepoRepository.save(new TrackedRepo(newcomer, 30L, "team3/app", "main", "head-at-register", 557L));

        clock.set(CYCLE);
        service.sendCycle(CYCLE);

        Digest digest = digestRepository.findByTeamIdAndScheduledFor(newcomer.getId(), CYCLE).orElseThrow();
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);
        // 등록 시점의 최신 커밋부터 수집하므로, 그사이 수집이 없었다면 변경 없음 안내가 간다 (멘션 없음)
        assertThat(digest.getType()).isEqualTo(DigestType.NO_CHANGE);
        assertThat(digest.getMentionUserId()).isNull();
        verify(digestSender).send(eq(300L), anyList());
        verify(changeSummarizer, never()).summarize(eq(newcomer.getId()), any());
        assertThat(opsEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(OpsEvent.Type.SEND_INLINE_FALLBACK);
            assertThat(event.teamName()).isEqualTo("새팀");
            assertThat(event.detail()).contains(DigestService.INLINE_NO_DIGEST_REASON);
        });
    }

    @Test
    void 레포가_없는_팀에서_발송테스트를_하면_레포_등록을_안내한다() {
        Team empty = teamRepository.save(new Team("빈팀", 300L, 9L));

        assertThatThrownBy(() -> service.sendNowByChannel(300L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("등록된 레포가 없어요. /레포등록 으로 먼저 등록해 주세요.");

        verify(changeCollector, never()).collectTeam(anyLong());
        assertThat(digestRepository.findAll()).noneMatch(d -> d.getTeam().getId().equals(empty.getId()));
    }

    // ---- 팀 락 ----

    @Test
    void 수동_발송_중이면_정기_준비는_거절되고_발송_시점에_처리된다() throws Exception {
        collected(backend, "로그인 API 추가");

        try (TeamLockHolder ignored = new TeamLockHolder(teamLocks, team.getId())) {
            preparer.prepareCycle(CYCLE);
        }

        assertThat(digestRepository.findByTeamIdAndScheduledFor(team.getId(), CYCLE)).isEmpty();
        verify(changeSummarizer, never()).summarize(anyLong(), any());
        assertThat(opsEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(OpsEvent.Type.PREPARE_FAILED);
            assertThat(event.detail()).contains("다른 작업");
        });
    }

    @Test
    void 발송_단계는_락이_풀리길_기다렸다가_진행한다() throws Exception {
        collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);
        clock.set(CYCLE);
        TeamLockHolder holder = new TeamLockHolder(teamLocks, team.getId());
        Thread releaser = Thread.ofVirtual().start(() -> {
            sleep(100);
            holder.close();
        });

        Optional<DigestSendResult> result = service.sendScheduled(team, CYCLE);

        releaser.join();
        assertThat(result).hasValueSatisfying(r -> assertThat(r.sent()).isTrue());
    }

    @Test
    void 발송_단계는_락을_끝내_못_잡아도_거절되지_않고_보낸다() throws Exception {
        collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);
        build(Duration.ofMillis(50), new DirectExecutorService());
        clock.set(CYCLE);

        try (TeamLockHolder ignored = new TeamLockHolder(teamLocks, team.getId())) {
            assertThat(service.sendScheduled(team, CYCLE)).hasValueSatisfying(r -> assertThat(r.sent()).isTrue());
        }
        assertThat(cycleDigest(CYCLE).getStatus()).isEqualTo(DigestStatus.SENT);
    }

    // ---- 수동 발송 (/발송테스트) ----

    @Test
    void 발송테스트는_정기_회차와_섞이지_않는_수동_회차로_바로_보낸다() {
        ChangeBatch login = collected(backend, "로그인 API 추가");
        clock.set(kst(8, 10));

        ManualSendResult result = service.sendNowByChannel(CHANNEL_ID);

        assertThat(result.sent().sent()).isTrue();
        assertThat(result.collected().teamName()).isEqualTo("1팀");
        Digest manual = digestRepository.findAll().getFirst();
        assertThat(manual.getCycleKind()).isEqualTo(CycleKind.MANUAL);
        assertThat(manual.getScheduledFor()).isEqualTo(kst(8, 10));
        assertThat(manual.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(batch(login).getStatus()).isEqualTo(ChangeBatchStatus.SENT);

        clock.set(kst(8, 30));
        preparer.prepareCycle(CYCLE);
        assertThat(cycleDigest(CYCLE).getType()).isEqualTo(DigestType.NO_CHANGE);   // 이미 보낸 변경은 다시 담지 않는다
    }

    @Test
    void 팀이_처리_중이면_발송테스트를_거절한다() throws Exception {
        try (TeamLockHolder ignored = new TeamLockHolder(teamLocks, team.getId())) {
            assertThatThrownBy(() -> service.sendNowByChannel(CHANNEL_ID))
                    .isInstanceOf(TeamBusyException.class)
                    .hasMessageContaining("처리 중");
        }
        verify(changeCollector, never()).collectTeam(anyLong());
        assertThat(digestRepository.findAll()).isEmpty();
    }

    // ---- /회차상태 ----

    @Test
    void 회차상태는_다음_회차_상태와_직전_회차_결과를_보여준다() {
        collected(backend, "로그인 API 추가");
        preparer.prepareCycle(CYCLE);
        given(digestSender.send(anyLong(), anyList())).willThrow(new DigestSendException("Discord 오류", List.of()));
        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);

        clock.set(kst(20, 40));
        preparer.prepareCycle(NEXT_CYCLE);
        CycleStatus status = service.cycleStatusByChannel(CHANNEL_ID);

        assertThat(status.nextCycle()).isEqualTo(NEXT_CYCLE);
        assertThat(status.nextPrepareDeadline()).isEqualTo(kst(20, 58));
        assertThat(status.nextStatus()).isEqualTo(DigestStatus.READY);
        assertThat(status.lastScheduled().scheduledFor()).isEqualTo(CYCLE);
        assertThat(status.lastScheduled().status()).isEqualTo(DigestStatus.FAILED);
        assertThat(status.lastScheduled().type()).isEqualTo(DigestType.FALLBACK);
        assertThat(status.lastScheduled().failureReason()).contains("Discord 오류");
        assertThat(status.lastManual()).isNull();
    }

    @Test
    void 회차상태는_직전_정기_회차의_PM_확인_현황을_보여준다() {
        collected(backend, "로그인 API 추가");
        givenSummaryLlm(new SummaryStatus[]{SummaryStatus.READY, SummaryStatus.PARTIAL, SummaryStatus.INTERNAL}, "기능");
        preparer.prepareCycle(CYCLE);
        clock.set(CYCLE);
        service.sendScheduled(team, CYCLE);
        List<DigestItem> sent = items(cycleDigest(CYCLE));
        digestItemRepository.confirm(sent.get(0).getId(), PM_ID, kst(9, 5));
        digestItemRepository.confirm(sent.get(2).getId(), PM_ID, kst(9, 6));

        clock.set(kst(10, 0));
        CycleStatus.Result last = service.cycleStatusByChannel(CHANNEL_ID).lastScheduled();

        assertThat(last.deliveredItems()).isEqualTo(3);
        assertThat(last.confirmedItems()).isEqualTo(2);
    }

    // ---- helpers ----

    private void build(Duration sendLockWait, ExecutorService prepareExecutor) {
        properties = new ScheduleProperties(SEOUL, "0 30 8,20 * * *", "0 0 9,21 * * *",
                Duration.ofMinutes(2), 3, sendLockWait, Duration.ofMinutes(30));
        calendar = new CycleCalendar(properties);
        preparer = new DigestPreparer(changeCollector, changeSummarizer, digestWriter, teamRepository, teamLocks,
                opsNotifier, calendar, prepareExecutor, clock);
        service = new DigestService(digestWriter, preparer, new DigestMessagePlanner(), digestSender, digestRepository,
                digestItemRepository, teamRepository, trackedRepoRepository, teamLocks, opsNotifier, calendar, properties,
                new DirectExecutorService(), clock);
        recovery = new DigestRecovery(digestRepository, digestWriter, service, teamRepository, opsNotifier, calendar,
                properties, clock);
    }

    private ChangeBatch collected(TrackedRepo repo, String message) {
        String sha = String.format("%040d", ++shaSeq);
        CommitSnapshot commit = new CommitSnapshot(sha, message, "alice", 11L, "Alice", clock.instant(),
                "https://github.com/" + repo.getFullName() + "/commit/" + sha);
        return changeBatchRepository.save(new ChangeBatch(repo, "base" + shaSeq, sha, clock.instant(),
                false, false, List.of(commit), List.of()));
    }

    private void givenSummaryFallback() {
        given(changeSummarizer.summarize(anyLong(), eq(LlmCallPurpose.DIGEST))).willAnswer(inv -> {
            List<Long> ids = collectedIds();
            return ids.isEmpty() ? SummaryResult.noChanges() : SummaryResult.fallback(ids, "테스트: 요약 끔", null);
        });
    }

    private void givenSummaryLlm(SummaryStatus[] statuses, String title) {
        given(changeSummarizer.summarize(anyLong(), eq(LlmCallPurpose.DIGEST))).willAnswer(inv -> {
            List<ChangeBatch> batches = changeBatchRepository
                    .findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(team.getId(), ChangeBatchStatus.COLLECTED);
            String firstSha = batches.getFirst().getCommits().getFirst().sha();
            List<SummaryItem> items = new ArrayList<>();
            for (SummaryStatus status : statuses) {
                items.add(new SummaryItem(status, title, List.of(firstSha)));
            }
            return SummaryResult.success(batches.stream().map(ChangeBatch::getId).toList(), items, null);
        });
    }

    private List<Long> collectedIds() {
        return changeBatchRepository.findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(team.getId(), ChangeBatchStatus.COLLECTED)
                .stream().map(ChangeBatch::getId).toList();
    }

    private Digest cycleDigest(Instant cycle) {
        return digestRepository.findByTeamIdAndScheduledFor(team.getId(), cycle).orElseThrow();
    }

    private ChangeBatch batch(ChangeBatch batch) {
        return changeBatchRepository.findById(batch.getId()).orElseThrow();
    }

    private List<DigestItem> items(Digest digest) {
        return digestItemRepository.findAllByDigestIdOrderByIdAsc(digest.getId());
    }

    private static String sha(ChangeBatch batch) {
        return batch.getCommits().getFirst().sha();
    }

    @SuppressWarnings("unchecked")
    private List<DigestMessage> sentMessages() {
        ArgumentCaptor<List<DigestMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(digestSender, atLeastOnce()).send(eq(CHANNEL_ID), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private List<OpsEvent> opsEvents() {
        ArgumentCaptor<OpsEvent> captor = ArgumentCaptor.forClass(OpsEvent.class);
        verify(opsNotifier, atLeastOnce()).notify(captor.capture());
        return captor.getAllValues();
    }

    private static Instant kst(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 27, hour, minute, 0, 0, SEOUL).toInstant();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 작업을 넘긴 뒤 그 작업이 요약을 시작하면 시각을 마감으로 옮긴다.
     * 코디네이터가 남은 시간을 0으로 계산해 실제로 기다리지 않고 바로 마감 처리로 넘어간다.
     */
    private static final class ClockAdvancingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final MutableClock clock;
        private final Instant advanceTo;
        private final CountDownLatch started;

        ClockAdvancingExecutor(ExecutorService delegate, MutableClock clock, Instant advanceTo, CountDownLatch started) {
            this.delegate = delegate;
            this.clock = clock;
            this.advanceTo = advanceTo;
            this.started = started;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return advanceAfter(delegate.submit(task));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return advanceAfter(delegate.submit(task));
        }

        private <T> Future<T> advanceAfter(Future<T> future) {
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clock.set(advanceTo);
            return future;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
