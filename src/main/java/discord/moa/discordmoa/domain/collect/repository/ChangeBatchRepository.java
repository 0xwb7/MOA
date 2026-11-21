package discord.moa.discordmoa.domain.collect.repository;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChangeBatchRepository extends JpaRepository<ChangeBatch, Long> {

    @EntityGraph(attributePaths = "trackedRepo")
    List<ChangeBatch> findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(Long teamId, ChangeBatchStatus status);

    @EntityGraph(attributePaths = "trackedRepo")
    List<ChangeBatch> findAllByDigestIdOrderByIdAsc(Long digestId);

    /**
     * LLM 요약에 쓴 배치를 회차에 담는다. 아직 COLLECTED인 것만 바뀐다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChangeBatch b set b.status = :to, b.digest = :digest where b.id in :ids and b.status = :from")
    int claimByIds(@Param("ids") Collection<Long> ids, @Param("digest") Digest digest,
                   @Param("from") ChangeBatchStatus from, @Param("to") ChangeBatchStatus to);

    /**
     * 팀의 COLLECTED 배치를 모두 회차에 담는다 (FALLBACK).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChangeBatch b set b.status = :to, b.digest = :digest
            where b.status = :from
              and b.trackedRepo in (select r from TrackedRepo r where r.team.id = :teamId)
            """)
    int claimAllOfTeam(@Param("teamId") Long teamId, @Param("digest") Digest digest,
                       @Param("from") ChangeBatchStatus from, @Param("to") ChangeBatchStatus to);
}
