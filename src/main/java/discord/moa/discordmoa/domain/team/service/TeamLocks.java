package discord.moa.discordmoa.domain.team.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 팀별 작업 락 (서버 1대 기준).
 * 준비, 발송, /발송테스트, /수집테스트가 같은 팀에 대해 동시에 돌지 않게 한다.
 * 락은 중복 LLM 호출과 헛수고를 막는 장치이고, 같은 회차를 두 번 처리하지 않는 정확성은 DB(유일 제약, 조건부 갱신)가 보장한다.
 * ReentrantLock은 잡은 스레드만 풀 수 있으므로 잡은 스레드에서 unlock해야 한다.
 */
@Component
public class TeamLocks {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 즉시 잡히면 실행하고, 다른 작업이 진행 중이면 기다리지 않고 TeamBusyException.
     */
    public <T> T callIfFree(Long teamId, String teamName, Supplier<T> action) {
        ReentrantLock lock = lockOf(teamId);
        if (!lock.tryLock()) {
            throw new TeamBusyException(teamName);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean tryLock(Long teamId) {
        return lockOf(teamId).tryLock();
    }

    /**
     * @return wait 안에 잡았는지. 인터럽트되면 false
     */
    public boolean tryLock(Long teamId, Duration wait) {
        try {
            return lockOf(teamId).tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void unlock(Long teamId) {
        lockOf(teamId).unlock();
    }

    boolean isLocked(Long teamId) {
        return lockOf(teamId).isLocked();
    }

    private ReentrantLock lockOf(Long teamId) {
        return locks.computeIfAbsent(teamId, id -> new ReentrantLock());
    }
}
