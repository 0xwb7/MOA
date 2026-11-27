package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TrackedRepoRepository extends JpaRepository<TrackedRepo, Long> {

    boolean existsByGithubRepoId(Long githubRepoId);

    // 비활성(/레포해제) 레포는 수집·정기 회차·목록에서 빠진다
    boolean existsByTeamIdAndActiveTrue(Long teamId);

    long countByTeamIdAndActiveTrue(Long teamId);

    List<TrackedRepo> findAllByTeamIdAndActiveTrueOrderByIdAsc(Long teamId);

    // /레포전체목록: 모든 팀의 활성 레포를 한 번에 (팀마다 조회하지 않는다)
    List<TrackedRepo> findAllByActiveTrueOrderByIdAsc();

    @EntityGraph(attributePaths = "team")
    List<TrackedRepo> findAllByFullNameIgnoreCaseAndActiveTrue(String fullName);

    @EntityGraph(attributePaths = "team")
    Optional<TrackedRepo> findByGithubRepoId(Long githubRepoId);

    // 커서(lastProcessedSha)는 건드리지 않도록 save(merge) 대신 이 컬럼만 바꾼다
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TrackedRepo r set r.installationId = :installationId where r.id = :id")
    int updateInstallationId(@Param("id") Long id, @Param("installationId") Long installationId);

    /**
     * 활성일 때만 비활성으로 바꾼다. 동시에 두 번 해제해도 한 번만 1을 돌려준다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TrackedRepo r set r.active = false, r.deactivatedAt = :now where r.id = :id and r.active = true")
    int deactivate(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 비활성 레포를 다시 활성화한다 (같은 팀이면 그대로, 다른 팀이면 그 팀으로 옮긴다).
     * 커서는 지금 시점의 최신 커밋으로 초기화한다: 해제돼 있던 동안의 변경을 한꺼번에 요약하지 않는다.
     * 진행 중이던 수집이 있으면 그 수집의 커서 갱신(advanceCursor)이 base 불일치로 실패하고 롤백된다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update TrackedRepo r
            set r.active = true, r.deactivatedAt = null, r.team = :team, r.installationId = :installationId,
                r.fullName = :fullName, r.defaultBranch = :defaultBranch, r.lastProcessedSha = :head,
                r.registeredAt = :now
            where r.id = :id and r.active = false
            """)
    int reactivate(@Param("id") Long id, @Param("team") Team team, @Param("installationId") Long installationId,
                   @Param("fullName") String fullName, @Param("defaultBranch") String defaultBranch,
                   @Param("head") String head, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update TrackedRepo r set r.lastCollectedAt = :now where r.id = :id")
    int markCollected(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            update TrackedRepo r set r.lastProcessedSha = :head
            where r.id = :id
              and ((:base is null and r.lastProcessedSha is null) or r.lastProcessedSha = :base)
            """)
    int advanceCursor(@Param("id") Long id, @Param("base") String base, @Param("head") String head);
}
