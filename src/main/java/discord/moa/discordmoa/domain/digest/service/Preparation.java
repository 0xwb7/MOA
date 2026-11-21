package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.digest.entity.DigestType;

import java.util.List;
import java.util.Optional;

/**
 * @param savedType READY로 저장한 유형. empty면 그사이 다른 쪽(마감·발송 시점 fallback)이 먼저 처리해 결과를 버린 것
 */
public record Preparation(
        Long digestId,
        List<RepoCollectResult> collected,
        Optional<DigestType> savedType
) {
}
