package discord.moa.discordmoa.domain.question.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.question.entity.DigestQuestion;
import discord.moa.discordmoa.domain.question.repository.DigestQuestionRepository;
import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.support.H2Constraints;
import discord.moa.discordmoa.support.MutableClock;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질문 제출 검사와 저장을 실제 JPA(H2)로 검증한다. 연속 질문 제한이 DB의 createdAt 기준이라 리포지토리를 mock으로 두지 않는다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    private static final long ASKER = 7L;
    private static final String SHA_LOGIN = "1".repeat(40);
    private static final String SHA_SCREEN = "2".repeat(40);
    private static final String SHA_MISSING = "3".repeat(40);

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TrackedRepoRepository trackedRepoRepository;
    @Autowired
    private DigestRepository digestRepository;
    @Autowired
    private DigestItemRepository digestItemRepository;
    @Autowired
    private ChangeBatchRepository changeBatchRepository;
    @Autowired
    private DigestQuestionRepository questionRepository;
    @Autowired
    private AccountLinkRepository accountLinkRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final MutableClock clock = new MutableClock(NOW, ZoneId.of("Asia/Seoul"));
    private QuestionService service;
    private DigestItem item;
    private DigestItem otherItem;

    @BeforeEach
    void setUp() {
        H2Constraints.dropChecks(entityManagerFactory);
        Team team = teamRepository.save(new Team("1팀", 100L, ASKER));
        TrackedRepo repo = trackedRepoRepository.save(new TrackedRepo(team, 1L, "team1/app", "main", null, 555L));
        Digest digest = digestRepository.save(new Digest(team, NOW.minusSeconds(3600), CycleKind.SCHEDULED, NOW.minusSeconds(5400)));
        Digest olderDigest = digestRepository.save(new Digest(team, NOW.minusSeconds(43200 + 3600), CycleKind.SCHEDULED,
                NOW.minusSeconds(43200 + 5400)));

        item = new DigestItem(digest, repo, "🟢 로그인 기능이 완성됐어요", List.of(SHA_LOGIN, SHA_SCREEN, SHA_MISSING),
                SummaryStatus.READY, "로그인 기능이 완성됐어요", null);
        item.markDelivered(9000L);
        item = digestItemRepository.save(item);
        otherItem = digestItemRepository.save(new DigestItem(digest, repo, "🟡 회원가입", List.of(SHA_LOGIN)));

        // 이 회차의 배치: 로그인(alice, 연동), 화면(carol, 미연동)
        batch(repo, digest, commit(SHA_LOGIN, "alice", 101L), commit(SHA_SCREEN, "carol", 103L));
        // 다른 회차에 같은 SHA_MISSING이 있어도 쓰지 않는다
        batch(repo, olderDigest, commit(SHA_MISSING, "bob", 102L));
        accountLinkRepository.save(new AccountLink(1001L, 101L, "alice"));
        accountLinkRepository.save(new AccountLink(1002L, 102L, "bob"));

        service = new QuestionService(digestItemRepository, changeBatchRepository, questionRepository,
                new QuestionTargetResolver(accountLinkRepository), clock);
    }

    @AfterEach
    void tearDown() {
        questionRepository.deleteAllInBatch();
        changeBatchRepository.deleteAllInBatch();
        digestItemRepository.deleteAllInBatch();
        digestRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
        accountLinkRepository.deleteAllInBatch();
    }

    @Test
    void 같은_회차_배치에서_작성자를_찾아_게시할_메시지를_만든다() {
        QuestionPreparation result = service.prepare(item.getId(), ASKER, "  로그인에 실패하면 어떤 화면이 나와요?  ");

        assertThat(result.status()).isEqualTo(QuestionPreparation.Status.READY);
        QuestionPost post = result.post();
        assertThat(post.itemId()).isEqualTo(item.getId());
        assertThat(post.teamName()).isEqualTo("1팀");
        assertThat(post.replyToMessageId()).isEqualTo(9000L);
        assertThat(post.mentionUserIds()).containsExactly(1001L);   // bob은 다른 회차의 커밋이라 빠진다
        assertThat(post.question()).isEqualTo("로그인에 실패하면 어떤 화면이 나와요?");
        assertThat(post.content())
                .contains("> 항목: 로그인 기능이 완성됐어요\n로그인에 실패하면 어떤 화면이 나와요?\n")
                .contains("→ <@1001> · carol (디스코드 미연동)")
                .contains("[1111111](https://github.com/team1/app/commit/" + SHA_LOGIN + ")")
                .contains("`3333333`")   // 커밋 정보를 못 찾은 SHA는 링크 없이
                .doesNotContain("bob");
        assertThat(questionRepository.count()).isZero();   // 검사만 하고 저장하지 않는다
    }

    @Test
    void 항목이_없으면_NOT_FOUND() {
        assertThat(service.prepare(999_999L, ASKER, "질문이 있어요").status())
                .isEqualTo(QuestionPreparation.Status.NOT_FOUND);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "    ", "네?", " 1234 "})
    void 앞뒤_공백을_빼고_5자_미만이면_거절한다(String question) {
        assertThat(service.prepare(item.getId(), ASKER, question).status())
                .isEqualTo(QuestionPreparation.Status.INVALID_CONTENT);
    }

    @Test
    void 천자를_넘으면_거절하고_천자는_받는다() {
        assertThat(service.prepare(item.getId(), ASKER, "가".repeat(1001)).status())
                .isEqualTo(QuestionPreparation.Status.INVALID_CONTENT);
        assertThat(service.prepare(item.getId(), ASKER, "가".repeat(1000)).status())
                .isEqualTo(QuestionPreparation.Status.READY);
    }

    @Test
    void 같은_사람이_같은_항목에_60초_안에_다시_질문하면_막는다() {
        service.record(item.getId(), ASKER, "첫 번째 질문이에요", 9100L);

        clock.set(NOW.plusSeconds(59));
        assertThat(service.prepare(item.getId(), ASKER, "두 번째 질문이에요").status())
                .isEqualTo(QuestionPreparation.Status.TOO_SOON);
        // 다른 항목, 다른 사람은 막지 않는다
        assertThat(service.prepare(otherItem.getId(), ASKER, "두 번째 질문이에요").status())
                .isEqualTo(QuestionPreparation.Status.READY);
        assertThat(service.prepare(item.getId(), 8L, "두 번째 질문이에요").status())
                .isEqualTo(QuestionPreparation.Status.READY);

        clock.set(NOW.plusSeconds(61));
        assertThat(service.prepare(item.getId(), ASKER, "두 번째 질문이에요").status())
                .isEqualTo(QuestionPreparation.Status.READY);
    }

    @Test
    void 게시한_질문을_저장한다() {
        service.record(item.getId(), ASKER, "로그인에 실패하면?", 9100L);

        DigestQuestion saved = questionRepository.findAll().getFirst();
        assertThat(saved.getAskerDiscordUserId()).isEqualTo(ASKER);
        assertThat(saved.getContent()).isEqualTo("로그인에 실패하면?");
        assertThat(saved.getPostedMessageId()).isEqualTo(9100L);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(questionRepository.existsByDigestItemIdAndAskerDiscordUserIdAndCreatedAtAfter(
                item.getId(), ASKER, NOW.minusSeconds(1))).isTrue();
    }

    private void batch(TrackedRepo repo, Digest digest, CommitSnapshot... commits) {
        ChangeBatch batch = new ChangeBatch(repo, "base", commits[commits.length - 1].sha(), NOW, false, false,
                List.of(commits), List.of());
        ReflectionTestUtils.setField(batch, "digest", digest);
        changeBatchRepository.save(batch);
    }

    private static CommitSnapshot commit(String sha, String login, Long githubId) {
        return new CommitSnapshot(sha, "msg", login, githubId, login, NOW, "https://github.com/team1/app/commit/" + sha);
    }
}
