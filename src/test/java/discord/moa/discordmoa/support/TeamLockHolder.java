package discord.moa.discordmoa.support;

import discord.moa.discordmoa.domain.team.service.TeamLocks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 다른 스레드에서 팀 락을 잡고 있다가 close()하면 푼다 (ReentrantLock은 잡은 스레드만 풀 수 있다).
 */
public final class TeamLockHolder implements AutoCloseable {

    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread thread;

    public TeamLockHolder(TeamLocks locks, Long teamId) throws InterruptedException {
        thread = Thread.ofPlatform().start(() -> {
            if (!locks.tryLock(teamId)) {
                throw new IllegalStateException("테스트 준비 실패: 락을 잡지 못했어요");
            }
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.unlock(teamId);
            }
        });
        if (!locked.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("테스트 준비 실패: 락을 잡지 못했어요");
        }
    }

    @Override
    public void close() {
        release.countDown();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
