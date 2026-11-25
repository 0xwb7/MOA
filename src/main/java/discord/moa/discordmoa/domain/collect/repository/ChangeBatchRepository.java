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

    /**
     * 팀의 활성 레포 배치만. 해제된 레포의 배치가 요약에 섞이지 않게 한다 (아래 claim 쿼리와 같은 조건).
     */
    @EntityGraph(attributePaths = "trackedRepo")
    @Query("""
            select b from ChangeBatch b
            where b.trackedRepo.team.id = :teamId
              and b.trackedRepo.active = true
              and b.status = :status
            order by b.id asc
            """)
    List<ChangeBatch> findAllOfActiveReposByTeamIdAndStatus(@Param("teamId") Long teamId,
                                                           @Param("status") ChangeBatchStatus status);

    @EntityGraph(attributePaths = "trackedRepo")
    List<ChangeBatch> findAllByDigestIdOrderByIdAsc(Long digestId);

    boolean existsByDigestIdAndTruncatedTrue(Long digestId);

    List<ChangeBatch> findAllByTrackedRepoIdAndStatus(Long trackedRepoId, ChangeBatchStatus status);

    /**
     * LLM 요약에 쓴 배치를 회차에 담는다. 아직 COLLECTED이고 레포가 활성인 것만 바뀐다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChangeBatch b set b.status = :to, b.digest = :digest
            where b.id in :ids and b.status = :from
              and b.trackedRepo in (select r from TrackedRepo r where r.active = true)
            """)
    int claimByIds(@Param("ids") Collection<Long> ids, @Param("digest") Digest digest,
                   @Param("from") ChangeBatchStatus from, @Param("to") ChangeBatchStatus to);

    /**
     * 팀 활성 레포의 COLLECTED 배치를 모두 회차에 담는다 (FALLBACK).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChangeBatch b set b.status = :to, b.digest = :digest
            where b.status = :from
              and b.trackedRepo in (select r from TrackedRepo r where r.team.id = :teamId and r.active = true)
            """)
    int claimAllOfTeam(@Param("teamId") Long teamId, @Param("digest") Digest digest,
                       @Param("from") ChangeBatchStatus from, @Param("to") ChangeBatchStatus to);

    /**
     * 지정한 배치 중 아직 from 상태인 것만 to로 바꾼다 (그사이 회차에 담긴 배치는 그대로).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChangeBatch b set b.status = :to where b.id in :ids and b.status = :from")
    int changeStatusByIds(@Param("ids") Collection<Long> ids, @Param("from") ChangeBatchStatus from,
                          @Param("to") ChangeBatchStatus to);
}
