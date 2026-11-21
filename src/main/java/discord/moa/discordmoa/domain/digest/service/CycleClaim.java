package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestStatus;

/**
 * 회차 선점 결과.
 *
 * @param created 이번에 새로 만들었는지 (false면 이미 있던 회차)
 */
public record CycleClaim(Long digestId, DigestStatus status, boolean created) {

    public boolean isPreparing() {
        return status == DigestStatus.PREPARING;
    }
}
