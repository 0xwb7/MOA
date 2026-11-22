package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;

import java.util.List;
import java.util.Optional;

/**
 * @param saved READY로 저장한 결과. empty면 그사이 다른 쪽(마감·발송 시점 fallback)이 먼저 처리해 결과를 버린 것
 */
public record Preparation(
        Long digestId,
        List<RepoCollectResult> collected,
        Optional<CompletedDigest> saved
) {
}
