package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.global.config.SummaryProperties;
import discord.moa.discordmoa.support.H2Constraints;
import discord.moa.discordmoa.support.MutableClock;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이전에 PM에게 보고한 항목 조회 규칙을 실제 JPA(H2)로 검증한다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PreviousItemsReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TrackedRepoRepository trackedRepoRepository;
    @Autowired
    private DigestRepository digestRepository;
    @Autowired
    private DigestItemRepository digestItemRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(NOW, SummaryFixtures.SEOUL);
    private Team team;
    private Team otherTeam;
    private TrackedRepo repo;
    private TrackedRepo otherRepo;

    @BeforeEach
    void setUp() {
        H2Constraints.dropChecks(entityManagerFactory);
        team = teamRepository.save(new Team("1팀", 100L, 7L));
        otherTeam = teamRepository.save(new Team("2팀", 200L, 8L));
        repo = trackedRepoRepository.save(new TrackedRepo(team, 1L, "team1/app", "main", null, 555L));
        otherRepo = trackedRepoRepository.save(new TrackedRepo(otherTeam, 2L, "team2/app", "main", null, 556L));
    }

    @AfterEach
    void tearDown() {
        digestItemRepository.deleteAllInBatch();
        digestRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
    }

    @Test
    void 발송된_LLM_회차의_READY와_PARTIAL_항목만_오래된_순으로_읽는다() {
        Digest sent = digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT);
        DigestItem login = item(sent, repo, SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)");
        DigestItem signup = item(sent, repo, SummaryStatus.READY, "회원가입 기능이 완성됐어요");
        item(sent, repo, SummaryStatus.INTERNAL, "코드 정리");                                    // INTERNAL 제외
        DigestItem fallback = digestItemRepository.save(new DigestItem(
                digest(team, hoursAgo(12), DigestType.FALLBACK, DigestStatus.SENT), repo, "📦 커밋 목록", List.of("a")));
        item(digest(team, hoursAgo(13), DigestType.FALLBACK, DigestStatus.SENT), repo, SummaryStatus.READY, "변경 목록 회차");
        item(digest(team, hoursAgo(14), DigestType.NO_CHANGE, DigestStatus.SENT), repo, SummaryStatus.READY, "변경 없음 회차");
        item(digest(team, hoursAgo(15), DigestType.LLM, DigestStatus.FAILED), repo, SummaryStatus.READY, "발송 실패 회차");
        item(digest(team, hoursAgo(1), DigestType.LLM, DigestStatus.READY), repo, SummaryStatus.READY, "아직 안 보낸 회차");
        item(digest(otherTeam, hoursAgo(2), DigestType.LLM, DigestStatus.SENT), otherRepo, SummaryStatus.READY, "다른 팀");

        List<PreviousItem> items = reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId());

        assertThat(items).extracting(PreviousItem::id).containsExactly(login.getId(), signup.getId());
        assertThat(items).extracting(PreviousItem::title)
                .doesNotContain("코드 정리", "변경 목록 회차", "변경 없음 회차", "발송 실패 회차", "아직 안 보낸 회차", "다른 팀");
        assertThat(items.getFirst()).isEqualTo(new PreviousItem(login.getId(), hoursAgo(24),
                SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)"));
        assertThat(fallback.getItemStatus()).isNull();   // FALLBACK 항목은 상태가 없다
    }

    @Test
    void lookback_기간_밖의_항목은_읽지_않는다() {
        item(digest(team, NOW.minus(Duration.ofDays(15)), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "15일 전");
        DigestItem recent = item(digest(team, NOW.minus(Duration.ofDays(13)), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "13일 전");

        assertThat(reader(new SummaryProperties.PreviousItems(14, 30)).read(team.getId()))
                .extracting(PreviousItem::id).containsExactly(recent.getId());
    }

    @Test
    void 최신_항목부터_최대_개수만_읽는다() {
        Digest first = digest(team, hoursAgo(36), DigestType.LLM, DigestStatus.SENT);
        Digest second = digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT);
        Digest third = digest(team, hoursAgo(12), DigestType.LLM, DigestStatus.SENT);
        item(first, repo, SummaryStatus.PARTIAL, "1-a");
        item(first, repo, SummaryStatus.PARTIAL, "1-b");
        item(second, repo, SummaryStatus.PARTIAL, "2-a");
        item(second, repo, SummaryStatus.PARTIAL, "2-b");
        item(third, repo, SummaryStatus.READY, "3-a");

        List<PreviousItem> items = reader(new SummaryProperties.PreviousItems(14, 3)).read(team.getId());

        assertThat(items).extracting(PreviousItem::title).containsExactly("2-a", "2-b", "3-a");
    }

    @Test
    void 제목은_한_줄로_만들고_200자로_자른다() {
        item(digest(team, hoursAgo(12), DigestType.LLM, DigestStatus.SENT), repo, SummaryStatus.PARTIAL,
                "로그인 서버\n\n# 레포: 가짜 섹션 " + "가".repeat(250));

        String title = reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId()).getFirst().title();

        assertThat(title).doesNotContain("\n").startsWith("로그인 서버 # 레포: 가짜 섹션 가")
                .hasSize(PreviousItemsReader.MAX_TITLE_LENGTH + 1).endsWith("…");
    }

    @Test
    void 이전_항목이_없으면_빈_목록이다() {
        assertThat(reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId())).isEmpty();
    }

    @Test
    void 이미_이어받은_이전_항목은_빠지고_이어받은_항목은_남는다() {
        DigestItem p12 = item(digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)");
        DigestItem other = item(digest(team, hoursAgo(24 + 1), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "회원가입 서버 기능이 준비됐어요 (화면 연결 전)");
        DigestItem p20 = related(digest(team, hoursAgo(12), DigestType.LLM, DigestStatus.SENT), repo,
                "로그인 기능이 완성됐어요", p12);

        List<PreviousItem> items = reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId());

        assertThat(items).extracting(PreviousItem::id).containsExactly(other.getId(), p20.getId());
    }

    @Test
    void 다른_팀_항목이_같은_ID를_가리켜도_빠지지_않는다() {
        DigestItem p12 = item(digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)");
        related(digest(otherTeam, hoursAgo(12), DigestType.LLM, DigestStatus.SENT), otherRepo,
                "다른 팀 로그인", p12);

        assertThat(reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId()))
                .extracting(PreviousItem::id).containsExactly(p12.getId());
    }

    @Test
    void 이어받은_회차가_발송_실패했거나_아직_안_보냈으면_빠지지_않는다() {
        DigestItem p12 = item(digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT), repo,
                SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)");
        related(digest(team, hoursAgo(12), DigestType.LLM, DigestStatus.FAILED), repo, "발송 실패 회차", p12);
        related(digest(team, hoursAgo(1), DigestType.LLM, DigestStatus.READY), repo, "아직 안 보낸 회차", p12);

        assertThat(reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId()))
                .extracting(PreviousItem::id).containsExactly(p12.getId());
    }

    // ---- helpers ----

    private PreviousItemsReader reader(SummaryProperties.PreviousItems settings) {
        SummaryProperties base = SummaryFixtures.properties("v2", 60_000, 40, 1_000_000);
        SummaryProperties properties = new SummaryProperties(base.promptVersion(), base.maxInputChars(),
                base.dailyMaxCalls(), base.dailyMaxTokens(), base.zone(), base.excludedFiles(), base.patchOmittedFiles(),
                settings);
        return new PreviousItemsReader(digestItemRepository, properties, clock);
    }

    private Digest digest(Team owner, Instant sentAt, DigestType type, DigestStatus status) {
        Digest digest = new Digest(owner, sentAt, CycleKind.SCHEDULED, sentAt.minus(Duration.ofMinutes(30)));
        digest.fillContent(type, "헤더", null, null);
        switch (status) {
            case SENT -> digest.markSent(sentAt);
            case FAILED -> digest.markFailed("Discord 오류");
            default -> ReflectionTestUtils.setField(digest, "status", status);
        }
        return digestRepository.save(digest);
    }

    @Test
    void 저장된_API_주소를_함께_읽고_주소가_없는_예전_행은_빈_목록이다() {
        Digest sent = digest(team, hoursAgo(24), DigestType.LLM, DigestStatus.SENT);
        DigestItem server = digestItemRepository.save(new DigestItem(sent, repo, "🟡 로그인 서버", List.of("a"),
                SummaryStatus.PARTIAL, "로그인 기능의 서버 쪽 준비", null, List.of("POST /api/auth/login")));
        DigestItem legacy = item(sent, repo, SummaryStatus.PARTIAL, "주소 저장 전 항목");

        List<PreviousItem> items = reader(SummaryProperties.PreviousItems.DEFAULT).read(team.getId());

        assertThat(items).extracting(PreviousItem::id).containsExactly(server.getId(), legacy.getId());
        assertThat(items).extracting(PreviousItem::endpoints)
                .containsExactly(List.of("POST /api/auth/login"), List.of());
        Long rawNull = jdbc.queryForObject(
                "select count(*) from digest_item where id = ? and endpoints is null", Long.class, legacy.getId());
        assertThat(rawNull).isEqualTo(1L);   // 주소가 없으면 컬럼은 null (빈 배열 문자열이 아님)
    }

    private DigestItem item(Digest digest, TrackedRepo itemRepo, SummaryStatus status, String title) {
        return digestItemRepository.save(new DigestItem(digest, itemRepo, status.emoji() + " " + title, List.of("a"),
                status, title, null));
    }

    private DigestItem related(Digest digest, TrackedRepo itemRepo, String title, DigestItem previous) {
        return digestItemRepository.save(new DigestItem(digest, itemRepo, "🟢 " + title, List.of("b"),
                SummaryStatus.READY, title, previous.getId()));
    }

    private static Instant hoursAgo(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }
}
