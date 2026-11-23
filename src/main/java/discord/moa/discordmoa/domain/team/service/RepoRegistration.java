package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;

/**
 * @param previousTeamName MOVED일 때 레포가 있던 팀 이름. 그 외에는 null
 */
public record RepoRegistration(
        TrackedRepo repo,
        Kind kind,
        String previousTeamName
) {

    public enum Kind {
        NEW,          // 처음 등록
        REFRESHED,    // 같은 팀이 이미 등록한 활성 레포: 설치 정보만 갱신, 수집 위치 유지
        REACTIVATED,  // 같은 팀이 해제했던 레포를 다시 등록: 커서를 지금으로 초기화
        MOVED         // 다른 팀이 해제했던 레포를 이 팀으로 옮김: 커서를 지금으로 초기화
    }

    public static RepoRegistration of(TrackedRepo repo, Kind kind) {
        return new RepoRegistration(repo, kind, null);
    }
}
