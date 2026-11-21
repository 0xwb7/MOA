package discord.moa.discordmoa.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트에서 시각을 직접 옮기는 Clock. 실제로 기다리지 않고 회차 시각을 재현한다.
 */
public class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void set(Instant now) {
        this.now = now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
