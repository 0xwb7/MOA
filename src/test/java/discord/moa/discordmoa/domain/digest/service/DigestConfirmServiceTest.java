package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.digest.repository.DigestRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "확인했어요" 처리를 실제 JPA(H2)로 검증한다. 조건부 UPDATE가 한 번만 기록되는지가 핵심이다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DigestConfirmServiceTest {

    private static final Long PM_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Instant CLICKED_AT = Instant.parse("2026-11-27T00:12:00Z");   // 09:12 KST

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

    private final MutableClock clock = new MutableClock(CLICKED_AT, ZoneId.of("Asia/Seoul"));
    private DigestConfirmService service;
    private Team team;
    private DigestItem item;
    private DigestItem otherItem;

    @BeforeEach
    void setUp() {
        H2Constraints.dropChecks(entityManagerFactory);
        team = teamRepository.save(new Team("1팀", 100L, PM_ID));
        TrackedRepo repo = trackedRepoRepository.save(new TrackedRepo(team, 1L, "team1/backend", "main", null, 555L));
        Digest digest = digestRepository.save(new Digest(team, Instant.parse("2026-11-27T00:00:00Z"), CycleKind.SCHEDULED,
                Instant.parse("2026-11-26T23:30:00Z")));
        item = digestItemRepository.save(new DigestItem(digest, repo, "🟢 로그인", List.of("a")));
        otherItem = digestItemRepository.save(new DigestItem(digest, repo, "🟡 회원가입", List.of("b")));
        service = new DigestConfirmService(digestItemRepository, clock);
    }

    @AfterEach
    void tearDown() {
        digestItemRepository.deleteAllInBatch();
        digestRepository.deleteAllInBatch();
        trackedRepoRepository.deleteAllInBatch();
        teamRepository.deleteAllInBatch();
    }

    @Test
    void PM이_누르면_확인_시각과_누른_사람을_기록한다() {
        assertThat(service.check(item.getId(), PM_ID).status()).isEqualTo(ConfirmCheck.Status.CONFIRMABLE);

        ConfirmCheck result = service.confirm(item.getId(), PM_ID);

        assertThat(result).isEqualTo(new ConfirmCheck(ConfirmCheck.Status.CONFIRMED, CLICKED_AT));
        DigestItem saved = reload(item);
        assertThat(saved.getConfirmedAt()).isEqualTo(CLICKED_AT);
        assertThat(saved.getConfirmedBy()).isEqualTo(PM_ID);
        assertThat(reload(otherItem).getConfirmedAt()).isNull();   // 같은 메시지의 다른 항목은 그대로
    }

    @Test
    void PM이_아니면_거절하고_아무것도_바꾸지_않는다() {
        assertThat(service.check(item.getId(), OTHER_ID).status()).isEqualTo(ConfirmCheck.Status.NOT_PM);

        DigestItem unchanged = reload(item);
        assertThat(unchanged.getConfirmedAt()).isNull();
        assertThat(unchanged.getConfirmedBy()).isNull();
    }

    @Test
    void 이미_확인된_항목은_확인_시각을_알려주고_다시_기록하지_않는다() {
        service.confirm(item.getId(), PM_ID);
        clock.set(CLICKED_AT.plusSeconds(600));

        ConfirmCheck check = service.check(item.getId(), PM_ID);
        ConfirmCheck again = service.confirm(item.getId(), PM_ID);

        assertThat(check).isEqualTo(new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CLICKED_AT));
        assertThat(again).isEqualTo(new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CLICKED_AT));
        assertThat(reload(item).getConfirmedAt()).isEqualTo(CLICKED_AT);
    }

    @Test
    void 조건부_UPDATE는_한_번만_1을_돌려준다() {
        assertThat(digestItemRepository.confirm(item.getId(), PM_ID, CLICKED_AT)).isEqualTo(1);
        assertThat(digestItemRepository.confirm(item.getId(), PM_ID, CLICKED_AT.plusSeconds(1))).isZero();

        assertThat(reload(item).getConfirmedAt()).isEqualTo(CLICKED_AT);
    }

    @Test
    void 동시에_두_번_눌러도_한_번만_기록한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConfirmCheck>> clicks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            clicks.add(pool.submit(() -> {
                start.await();
                return service.confirm(item.getId(), PM_ID);
            }));
        }
        start.countDown();

        List<ConfirmCheck.Status> results = new ArrayList<>();
        for (Future<ConfirmCheck> click : clicks) {
            results.add(click.get().status());
        }
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder(ConfirmCheck.Status.CONFIRMED, ConfirmCheck.Status.ALREADY_CONFIRMED);
        assertThat(reload(item).getConfirmedBy()).isEqualTo(PM_ID);
    }

    @Test
    void PM이_바뀌면_지금_PM만_확인할_수_있다() {
        Team changed = teamRepository.findById(team.getId()).orElseThrow();
        changed.update(100L, OTHER_ID);
        teamRepository.save(changed);

        assertThat(service.check(item.getId(), PM_ID).status()).isEqualTo(ConfirmCheck.Status.NOT_PM);
        assertThat(service.check(item.getId(), OTHER_ID).status()).isEqualTo(ConfirmCheck.Status.CONFIRMABLE);
    }

    @Test
    void 없는_항목이면_찾을_수_없다고_한다() {
        assertThat(service.check(999_999L, PM_ID).status()).isEqualTo(ConfirmCheck.Status.NOT_FOUND);
        assertThat(service.confirm(999_999L, PM_ID).status()).isEqualTo(ConfirmCheck.Status.NOT_FOUND);
    }

    private DigestItem reload(DigestItem target) {
        return digestItemRepository.findById(target.getId()).orElseThrow();
    }
}
