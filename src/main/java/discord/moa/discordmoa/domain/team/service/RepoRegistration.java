package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;

/**
 * @param refreshed 같은 팀이 이미 등록한 레포라서 설치 정보만 갱신했는지
 */
public record RepoRegistration(
        TrackedRepo repo,
        boolean refreshed
) {
}
