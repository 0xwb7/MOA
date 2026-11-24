package discord.moa.discordmoa.domain.question.repository;

import discord.moa.discordmoa.domain.question.entity.DigestQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface DigestQuestionRepository extends JpaRepository<DigestQuestion, Long> {

    // 같은 사람이 같은 항목에 since 이후 올린 질문이 있는지 (연속 질문 제한)
    boolean existsByDigestItemIdAndAskerDiscordUserIdAndCreatedAtAfter(Long digestItemId, Long askerDiscordUserId,
                                                                      Instant since);
}
