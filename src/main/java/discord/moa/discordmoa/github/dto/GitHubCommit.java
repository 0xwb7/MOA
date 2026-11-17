package discord.moa.discordmoa.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record GitHubCommit(
        String sha,
        @JsonProperty("html_url") String htmlUrl,
        Detail commit,
        Account author,  // 커밋 이메일이 GitHub 계정에 연결되지 않으면 null
        List<GitHubFile> files
) {

    public record Detail(String message, Signature author, Signature committer) {
    }

    public record Signature(String name, String email, Instant date) {
    }

    public record Account(Long id, String login) {
    }
}
