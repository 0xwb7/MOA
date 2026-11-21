package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;

import java.time.Instant;

/**
 * /회차상태 응답.
 *
 * @param nextStatus     다음 정기 회차 상태. 아직 준비 전이면 null
 * @param lastScheduled  직전 정기 회차. 없으면 null
 * @param lastManual     마지막 /발송테스트 회차. 없으면 null
 */
public record CycleStatus(
        String teamName,
        Instant nextCycle,
        Instant nextPrepareDeadline,
        DigestStatus nextStatus,
        Result lastScheduled,
        Result lastManual
) {

    public record Result(
            Instant scheduledFor,
            DigestStatus status,
            DigestType type,
            Instant sentAt,
            String failureReason,
            String fallbackReason
    ) {
        static Result of(Digest digest) {
            return new Result(digest.getScheduledFor(), digest.getStatus(), digest.getType(), digest.getSentAt(),
                    digest.getFailureReason(), digest.getFallbackReason());
        }
    }
}
