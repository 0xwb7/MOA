package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;

/**
 * @param previousTeamName MOVED일 때 레포가 있던 팀 이름. 그 외에는 null
 * @param cleanedUpStale   같은 팀에 이름은 같지만 GitHub 레포 ID가 다른 이전 등록(삭제 후 재생성된 레포)을 정리했는지
 */
public record RepoRegistration(
        TrackedRepo repo,
        Kind kind,
        String previousTeamName,
        boolean cleanedUpStale
) {

    public RepoRegistration(TrackedRepo repo, Kind kind, String previousTeamName) {
        this(repo, kind, previousTeamName, false);
    }

    public enum Kind {
        NEW,          // 처음 등록
        REFRESHED,    // 같은 팀이 이미 등록한 활성 레포: 설치 정보만 갱신, 수집 위치 유지
        REACTIVATED,  // 같은 팀이 해제했던 레포를 다시 등록: 커서를 지금으로 초기화
        MOVED         // 다른 팀이 해제했던 레포를 이 팀으로 옮김: 커서를 지금으로 초기화
    }

    public static RepoRegistration of(TrackedRepo repo, Kind kind) {
        return new RepoRegistration(repo, kind, null);
    }

    public RepoRegistration withCleanedUpStale(boolean cleanedUpStale) {
        return new RepoRegistration(repo, kind, previousTeamName, cleanedUpStale);
    }
}
