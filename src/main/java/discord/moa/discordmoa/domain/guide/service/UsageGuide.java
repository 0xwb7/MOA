package discord.moa.discordmoa.domain.guide.service;

import java.time.Instant;
import java.time.ZoneId;

/**
 * /사용법 응답에 필요한 값.
 *
 * @param teamName        이 채널이 팀 채널이면 팀 이름, 아니면 null
 * @param pm              팀 채널이고 실행한 사람이 그 팀 PM인지
 * @param githubLogin     실행한 사람이 연동한 GitHub 아이디. 연동 안 했으면 null
 * @param activeRepoCount 팀의 활성 레포 수 (팀 채널이 아니면 0)
 * @param nextSend        다음 정기 발송 시각 (팀 채널이 아니면 null)
 * @param guideUrl        자세한 사용법 링크. 비어 있으면 null
 */
public record UsageGuide(
        String teamName,
        boolean pm,
        String githubLogin,
        long activeRepoCount,
        Instant nextSend,
        Instant now,
        ZoneId zone,
        boolean confirmButtonEnabled,
        boolean askButtonEnabled,
        String guideUrl
) {

    public boolean teamChannel() {
        return teamName != null;
    }
}
