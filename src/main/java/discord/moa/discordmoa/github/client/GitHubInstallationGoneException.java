package discord.moa.discordmoa.github.client;

import lombok.Getter;

/**
 * 설치 토큰 발급이 404: App 설치가 해제됐다 (다시 설치하면 새 설치 ID가 발급된다).
 */
@Getter
public class GitHubInstallationGoneException extends RuntimeException {

    private final Long installationId;

    public GitHubInstallationGoneException(Long installationId, Throwable cause) {
        super("GitHub App 설치를 찾을 수 없어요 (installationId=" + installationId + ")", cause);
        this.installationId = installationId;
    }
}
