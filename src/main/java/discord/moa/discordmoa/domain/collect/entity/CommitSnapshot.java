package discord.moa.discordmoa.domain.collect.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record CommitSnapshot(
        String sha,
        String message,
        String authorLogin,     // 커밋 이메일이 GitHub 계정에 연결되지 않으면 null
        Long authorGithubId,    // 위와 같음
        String authorName,
        @JsonFormat(shape = JsonFormat.Shape.STRING)  // DB에서 읽기 쉽게 ISO-8601 문자열로 저장
        Instant committedAt,
        String htmlUrl
) {
}
