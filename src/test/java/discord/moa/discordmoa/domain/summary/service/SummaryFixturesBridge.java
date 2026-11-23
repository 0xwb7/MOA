package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.global.config.SummaryProperties;

/**
 * 다른 패키지 테스트에서 요약 설정 픽스처를 쓰기 위한 통로 (SummaryFixtures는 패키지 전용).
 */
public final class SummaryFixturesBridge {

    private SummaryFixturesBridge() {
    }

    public static SummaryProperties v2Properties() {
        return SummaryFixtures.properties("v2", 60_000, 40, 1_000_000);
    }
}
