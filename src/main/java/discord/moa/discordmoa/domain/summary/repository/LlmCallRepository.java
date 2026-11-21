package discord.moa.discordmoa.domain.summary.repository;

import discord.moa.discordmoa.domain.summary.entity.LlmCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

    long countByCreatedAtGreaterThanEqual(Instant since);

    @Query("select coalesce(sum(coalesce(c.inputTokens, 0) + coalesce(c.outputTokens, 0)), 0) "
            + "from LlmCall c where c.createdAt >= :since")
    long sumTokensSince(@Param("since") Instant since);
}
