package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.support.TeamLockHolder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamLocksTest {

    private final TeamLocks locks = new TeamLocks();

    @Test
    void 다른_스레드가_처리_중이면_기다리지_않고_거절한다() throws Exception {
        try (TeamLockHolder holder = new TeamLockHolder(locks, 1L)) {
            assertThatThrownBy(() -> locks.callIfFree(1L, "1팀", () -> "실행됨"))
                    .isInstanceOf(TeamBusyException.class)
                    .hasMessage("'1팀' 팀은 지금 처리 중이에요. 잠시 후 다시 시도해 주세요.");
            assertThat(locks.callIfFree(2L, "2팀", () -> "다른 팀은 실행됨")).isEqualTo("다른 팀은 실행됨");
        }
        assertThat(locks.callIfFree(1L, "1팀", () -> "끝난 뒤 실행됨")).isEqualTo("끝난 뒤 실행됨");
        assertThat(locks.isLocked(1L)).isFalse();
    }

    @Test
    void 기다리는_동안_풀리면_잡는다() throws Exception {
        TeamLockHolder holder = new TeamLockHolder(locks, 1L);
        Thread releaser = Thread.ofVirtual().start(() -> {
            sleep(50);
            holder.close();
        });

        assertThat(locks.tryLock(1L, Duration.ofSeconds(5))).isTrue();
        locks.unlock(1L);
        releaser.join();
    }

    @Test
    void 기다려도_안_풀리면_false() throws Exception {
        try (TeamLockHolder ignored = new TeamLockHolder(locks, 1L)) {
            assertThat(locks.tryLock(1L, Duration.ofMillis(50))).isFalse();
        }
    }

    @Test
    void 작업이_예외로_끝나도_락을_푼다() {
        assertThatThrownBy(() -> locks.callIfFree(1L, "1팀", () -> {
            throw new IllegalArgumentException("실패");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(locks.isLocked(1L)).isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
