package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryItem;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.batch;
import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.repo;
import static discord.moa.discordmoa.domain.digest.service.DigestFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DigestSender만 mock으로 두고, 리포지토리는 메모리에 상태를 들고 있는 stub으로 대체해
 * 여러 번의 sendTeam 사이에 상태가 이어지는 시나리오를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long CHANNEL_ID = 100L;
    private static final Long PM_ID = 7L;

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private ChangeBatchRepository changeBatchRepository;
    @Mock
    private DigestRepository digestRepository;
    @Mock
    private DigestItemRepository digestItemRepository;
    @Mock
    private DigestSender digestSender;
    @Mock
    private ChangeSummarizer changeSummarizer;

    private final List<ChangeBatch> batches = new ArrayList<>();
    private final Map<Long, Digest> digests = new LinkedHashMap<>();
    private final Map<Long, DigestItem> items = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);

    private DigestService service;
    private Team team;
    private TrackedRepo repoA;
    private TrackedRepo repoB;

    @BeforeEach
    void setUp() {
        team = team(TEAM_ID, "1팀", CHANNEL_ID, PM_ID);
        repoA = repo(1L, team, "0xwb7/springSecurityExample");
        repoB = repo(2L, team, "0xwb7/test");

        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
        given(teamRepository.findByDiscordChannelId(CHANNEL_ID)).willReturn(Optional.of(team));
        given(changeBatchRepository.findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(TEAM_ID, ChangeBatchStatus.COLLECTED))
                .willAnswer(inv -> batches.stream().filter(b -> b.getStatus() == ChangeBatchStatus.COLLECTED).toList());
        given(changeBatchRepository.findAllById(any())).willAnswer(inv -> {
            Collection<Long> wanted = inv.getArgument(0);
            return batches.stream().filter(b -> wanted.contains(b.getId())).toList();
        });
        given(digestRepository.save(any(Digest.class))).willAnswer(inv -> store(inv.getArgument(0), digests));
        given(digestRepository.findById(any())).willAnswer(inv -> Optional.ofNullable(digests.get(inv.<Long>getArgument(0))));
        given(digestRepository.findAllByTeamIdAndStatus(eq(TEAM_ID), any())).willAnswer(inv -> digests.values().stream()
                .filter(d -> d.getStatus() == inv.getArgument(1)).toList());
        given(digestItemRepository.save(any(DigestItem.class))).willAnswer(inv -> store(inv.getArgument(0), items));
        given(digestItemRepository.findById(any())).willAnswer(inv -> Optional.ofNullable(items.get(inv.<Long>getArgument(0))));

        // 기본은 요약 실패(fallback). LLM 경로 테스트에서 따로 지정한다
        given(changeSummarizer.summarize(eq(TEAM_ID), eq(LlmCallPurpose.DIGEST))).willAnswer(inv -> {
            List<Long> ids = collectedIds();
            return ids.isEmpty() ? SummaryResult.noChanges() : SummaryResult.fallback(ids, "테스트: 요약 끔", null);
        });

        DigestWriter writer = new DigestWriter(teamRepository, changeBatchRepository, digestRepository,
                digestItemRepository, new FallbackDigestFormatter(), new SummaryDigestFormatter());
        service = new DigestService(writer, new DigestMessagePlanner(), digestSender, teamRepository, changeSummarizer);
    }

    @Test
    void 발송_성공하면_Digest와_ChangeBatch가_SENT가_되고_항목에_메시지ID가_기록된다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가", "비밀번호 암호화 적용"));
        batches.add(batch(2L, repoB, "b0", "b1", "README 수정"));
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isTrue();
        assertThat(result.itemCount()).isEqualTo(2);
        Digest digest = onlyDigest();
        assertThat(digest.getType()).isEqualTo(DigestType.FALLBACK);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(digest.getSentAt()).isNotNull();
        assertThat(items.values()).extracting(DigestItem::getDiscordMessageId).containsOnly(9001L);
        assertThat(batches).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(ChangeBatchStatus.SENT);
            assertThat(b.getDigestItem()).isNotNull();
        });
        assertThat(batches.get(0).getDigestItem().getTrackedRepo()).isSameAs(repoA);
        assertThat(batches.get(0).getDigestItem().getCommitShas()).containsExactly("a1-0", "a1-1");

        DigestMessage message = sentMessages().getFirst();
        assertThat(message.mentionUserId()).isEqualTo(PM_ID);
        assertThat(message.blocks()).hasSize(3);
        assertThat(message.blocks().get(0).text()).startsWith("<@7> 지난 12시간 동안 '1팀'에 변경이 있었어요");
        assertThat(message.blocks().get(0).hasButtons()).isFalse();
        assertThat(message.blocks().get(1).text()).startsWith("📦 0xwb7/springSecurityExample (커밋 2개)");
        assertThat(message.blocks().subList(1, 3)).allMatch(DigestBlock::hasButtons);
    }

    @Test
    void 발송_실패하면_Digest는_FAILED이고_ChangeBatch는_COLLECTED로_남는다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가"));
        given(digestSender.send(eq(CHANNEL_ID), anyList()))
                .willThrow(new DigestSendException("봇에게 #1팀 채널의 Send Messages 권한이 없어요.", List.of()));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isFalse();
        assertThat(result.failureReason()).contains("Send Messages");
        assertThat(result.summaryFallbackReason()).isEqualTo("테스트: 요약 끔");
        Digest digest = onlyDigest();
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(digest.getFailureReason()).isEqualTo("봇에게 #1팀 채널의 Send Messages 권한이 없어요.");
        assertThat(batches.getFirst().getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(batches.getFirst().getDigestItem()).isNull();
        assertThat(items.values()).extracting(DigestItem::getDiscordMessageId).containsOnlyNulls();
    }

    @Test
    void 변경이_없으면_멘션과_버튼_없이_변경_없음_안내만_보낸다() {
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isTrue();
        assertThat(result.type()).isEqualTo(DigestType.NO_CHANGE);
        assertThat(onlyDigest().getType()).isEqualTo(DigestType.NO_CHANGE);
        assertThat(onlyDigest().getStatus()).isEqualTo(DigestStatus.SENT);
        assertThat(items).isEmpty();

        DigestMessage message = sentMessages().getFirst();
        assertThat(message.mentionUserId()).isNull();
        assertThat(message.blocks()).containsExactly(DigestBlock.textOnly("지난 12시간 동안 '1팀'에 변경 사항이 없어요"));
    }

    @Test
    void 실패한_뒤_다시_보내면_이전_실패분이_새_변경과_함께_발송된다() {
        batches.add(batch(1L, repoA, "a0", "a1", "실패했던 커밋"));
        given(digestSender.send(eq(CHANNEL_ID), anyList()))
                .willThrow(new DigestSendException("Discord 오류", List.of()))
                .willReturn(List.of(9002L));
        service.sendTeam(TEAM_ID);

        batches.add(batch(2L, repoA, "a1", "a2", "새 커밋"));
        DigestSendResult retry = service.sendTeam(TEAM_ID);

        assertThat(retry.sent()).isTrue();
        DigestMessage retried = sentMessages().get(1);
        String itemText = retried.blocks().get(1).text();
        assertThat(itemText).contains("(커밋 2개)", "- 실패했던 커밋\n", "- 새 커밋\n", "/compare/a0...a2)");
        assertThat(batches).allSatisfy(b -> assertThat(b.getStatus()).isEqualTo(ChangeBatchStatus.SENT));
        assertThat(digests.values()).extracting(Digest::getStatus)
                .containsExactly(DigestStatus.FAILED, DigestStatus.SENT);
    }

    @Test
    void 성공한_뒤_다시_보내면_같은_내용을_다시_보내지_않는다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가"));
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L), List.of(9002L));
        service.sendTeam(TEAM_ID);
        DigestItem firstItem = batches.getFirst().getDigestItem();

        DigestSendResult second = service.sendTeam(TEAM_ID);

        assertThat(second.type()).isEqualTo(DigestType.NO_CHANGE);
        DigestMessage message = sentMessages().get(1);
        assertThat(message.blocks()).noneMatch(DigestBlock::hasButtons);
        assertThat(message.blocks().getFirst().text()).doesNotContain("로그인 API 추가");
        assertThat(batches.getFirst().getDigestItem()).isSameAs(firstItem);
        assertThat(items).hasSize(1);
    }

    @Test
    void 여러_메시지_중_일부만_보내졌으면_보낸_항목만_SENT로_기록한다() {
        // 레포 8개 → 메시지 2개 (7 + 1)
        List<TrackedRepo> repos = LongStream.rangeClosed(1, 8)
                .mapToObj(id -> repo(id, team, "owner/repo" + id)).toList();
        repos.forEach(r -> batches.add(batch(r.getId(), r, "x", "y", "커밋")));
        given(digestSender.send(eq(CHANNEL_ID), anyList()))
                .willThrow(new DigestSendException("Discord 오류", List.of(9001L)));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isFalse();
        assertThat(onlyDigest().getFailureReason()).contains("메시지 2개 중 1개는 발송됨");
        assertThat(batches.subList(0, 7)).allSatisfy(b -> assertThat(b.getStatus()).isEqualTo(ChangeBatchStatus.SENT));
        assertThat(batches.get(7).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(items.values()).extracting(DigestItem::getDiscordMessageId)
                .containsExactly(9001L, 9001L, 9001L, 9001L, 9001L, 9001L, 9001L, null);
    }

    @Test
    void 최근_PENDING_발송이_있으면_거절하고_오래된_PENDING은_중단으로_정리한다() {
        Digest recent = store(new Digest(team, DigestType.FALLBACK, Instant.now().minusSeconds(60)), digests);

        assertThatThrownBy(() -> service.sendTeam(TEAM_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 진행 중");
        verify(digestSender, never()).send(any(), anyList());
        verify(changeSummarizer, never()).summarize(any(), any());

        ReflectionTestUtils.setField(recent, "createdAt", Instant.now().minusSeconds(3600));
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        assertThat(service.sendTeam(TEAM_ID).sent()).isTrue();
        assertThat(recent.getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(recent.getFailureReason()).contains("중단");
    }

    @Test
    void 팀_채널이_아니면_발송하지_않는다() {
        given(teamRepository.findByDiscordChannelId(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTeamByChannel(999L)).isInstanceOf(IllegalStateException.class);
        verify(digestSender, never()).send(any(), anyList());
    }

    // ---- LLM 요약 ----

    @Test
    void LLM_요약이_성공하면_기능_단위_항목으로_보내고_배치를_모두_SENT로_바꾼다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가", "README 수정"));
        batches.add(batch(2L, repoB, "b0", "b1", "로그인 화면 추가"));
        givenSummary(List.of(1L, 2L),
                new SummaryItem(SummaryStatus.READY, "로그인 기능을 써 볼 수 있어요", List.of("a1-0", "b1-0")),
                new SummaryItem(SummaryStatus.PARTIAL, "회원가입은 서버만 준비됐어요", List.of("a1-0")),
                new SummaryItem(SummaryStatus.INTERNAL, "문서 정리", List.of("a1-1")));
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isTrue();
        assertThat(result.type()).isEqualTo(DigestType.LLM);
        assertThat(result.itemCount()).isEqualTo(3);
        assertThat(result.summaryFallbackReason()).isNull();
        Digest digest = onlyDigest();
        assertThat(digest.getType()).isEqualTo(DigestType.LLM);
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SENT);

        DigestMessage message = sentMessages().getFirst();
        assertThat(message.mentionUserId()).isEqualTo(PM_ID);
        assertThat(message.blocks().getFirst().text())
                .startsWith("<@7> 지난 12시간 동안 '1팀'에 이런 변경이 있었어요")
                .doesNotContain("요약을 만들지 못해");
        assertThat(message.blocks().subList(1, 4)).extracting(DigestBlock::text).containsExactly(
                "🟢 로그인 기능을 써 볼 수 있어요",
                "🟡 회원가입은 서버만 준비됐어요",
                "⚪ 문서 정리");
        assertThat(message.blocks().subList(1, 4)).allMatch(DigestBlock::hasButtons);

        List<DigestItem> saved = List.copyOf(items.values());
        assertThat(saved).extracting(DigestItem::getCommitShas)
                .containsExactly(List.of("a1-0", "b1-0"), List.of("a1-0"), List.of("a1-1"));
        assertThat(saved.get(0).getTrackedRepo()).isNull();          // 두 레포에 걸친 기능
        assertThat(saved.get(1).getTrackedRepo()).isSameAs(repoA);
        assertThat(saved).extracting(DigestItem::getDiscordMessageId).containsOnly(9001L);

        assertThat(batches).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(ChangeBatchStatus.SENT);
            assertThat(b.getDigest()).isSameAs(digest);
            assertThat(b.getDigestItem()).isNull();
        });
    }

    @Test
    void LLM_요약이_실패하면_변경_목록으로_보내고_사유를_알려준다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가"));
        given(changeSummarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST))
                .willReturn(SummaryResult.fallback(List.of(1L), "응답 검증 실패: 입력에 없는 커밋 SHA", null));
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isTrue();
        assertThat(result.type()).isEqualTo(DigestType.FALLBACK);
        assertThat(result.summaryFallbackReason()).isEqualTo("응답 검증 실패: 입력에 없는 커밋 SHA");
        assertThat(sentMessages().getFirst().blocks().getFirst().text()).contains("요약을 만들지 못해");
        assertThat(batches.getFirst().getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(batches.getFirst().getDigest()).isSameAs(onlyDigest());
        assertThat(batches.getFirst().getDigestItem()).isNotNull();
    }

    @Test
    void LLM_Digest가_일부만_발송되면_배치를_모두_COLLECTED로_둔다() {
        batches.add(batch(1L, repoA, "a0", "a1", "커밋"));
        SummaryItem[] summaryItems = LongStream.rangeClosed(1, 8)
                .mapToObj(i -> new SummaryItem(SummaryStatus.PARTIAL, "기능 " + i, List.of("a1-0")))
                .toArray(SummaryItem[]::new);
        givenSummary(List.of(1L), summaryItems);
        given(digestSender.send(eq(CHANNEL_ID), anyList()))
                .willThrow(new DigestSendException("Discord 오류", List.of(9001L)));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.sent()).isFalse();
        assertThat(result.messageCount()).isEqualTo(1);
        assertThat(onlyDigest().getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(batches.getFirst().getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
        assertThat(batches.getFirst().getDigest()).isNull();
        assertThat(items.values()).extracting(DigestItem::getDiscordMessageId)
                .containsExactly(9001L, 9001L, 9001L, 9001L, 9001L, 9001L, 9001L, null);
    }

    @Test
    void 요약하는_동안_새로_수집된_배치는_다음_회차로_넘긴다() {
        batches.add(batch(1L, repoA, "a0", "a1", "로그인 API 추가"));
        given(changeSummarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST)).willAnswer(inv -> {
            batches.add(batch(2L, repoB, "b0", "b1", "요약 중에 들어온 커밋"));
            return SummaryResult.success(List.of(1L),
                    List.of(new SummaryItem(SummaryStatus.PARTIAL, "로그인 서버 준비", List.of("a1-0"))), null);
        });
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.type()).isEqualTo(DigestType.LLM);
        assertThat(result.itemCount()).isEqualTo(1);
        assertThat(batches.get(0).getStatus()).isEqualTo(ChangeBatchStatus.SENT);
        assertThat(batches.get(1).getStatus()).isEqualTo(ChangeBatchStatus.COLLECTED);
    }

    @Test
    void 요약한_배치가_그새_발송됐으면_LLM_결과를_버리고_남은_변경을_목록으로_보낸다() {
        ChangeBatch first = batch(1L, repoA, "a0", "a1", "이미 다른 발송에 포함된 커밋");
        batches.add(first);
        batches.add(batch(2L, repoB, "b0", "b1", "남은 커밋"));
        given(changeSummarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST)).willAnswer(inv -> {
            first.markSent(null, null);
            return SummaryResult.success(List.of(1L, 2L),
                    List.of(new SummaryItem(SummaryStatus.PARTIAL, "로그인", List.of("a1-0", "b1-0"))), null);
        });
        given(digestSender.send(eq(CHANNEL_ID), anyList())).willReturn(List.of(9001L));

        DigestSendResult result = service.sendTeam(TEAM_ID);

        assertThat(result.type()).isEqualTo(DigestType.FALLBACK);
        assertThat(result.summaryFallbackReason()).contains("배치 상태가 바뀌었어요");
        assertThat(result.itemCount()).isEqualTo(1);
        assertThat(sentMessages().getFirst().blocks().get(1).text()).contains("남은 커밋").doesNotContain("이미 다른");
    }

    // ---- helpers ----

    private void givenSummary(List<Long> batchIds, SummaryItem... summaryItems) {
        given(changeSummarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST))
                .willReturn(SummaryResult.success(batchIds, List.of(summaryItems), null));
    }

    private List<Long> collectedIds() {
        return batches.stream()
                .filter(b -> b.getStatus() == ChangeBatchStatus.COLLECTED)
                .map(ChangeBatch::getId)
                .toList();
    }

    private <T> T store(T entity, Map<Long, T> table) {
        Object id = ReflectionTestUtils.getField(entity, "id");
        if (id == null) {
            id = ids.incrementAndGet();
            ReflectionTestUtils.setField(entity, "id", id);
        }
        table.put((Long) id, entity);
        return entity;
    }

    private Digest onlyDigest() {
        assertThat(digests).hasSize(1);
        return digests.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private List<DigestMessage> sentMessages() {
        ArgumentCaptor<List<DigestMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(digestSender, atLeastOnce()).send(eq(CHANNEL_ID), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }
}
