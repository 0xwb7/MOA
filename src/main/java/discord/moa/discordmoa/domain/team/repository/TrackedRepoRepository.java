package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TrackedRepoRepository extends JpaRepository<TrackedRepo, Long> {

    boolean existsByGithubRepoId(Long githubRepoId);

    @EntityGraph(attributePaths = "team")
    Optional<TrackedRepo> findByGithubRepoId(Long githubRepoId);

    // 커서(lastProcessedSha)는 건드리지 않도록 save(merge) 대신 이 컬럼만 바꾼다
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update TrackedRepo r set r.installationId = :installationId where r.id = :id")
    int updateInstallationId(@Param("id") Long id, @Param("installationId") Long installationId);

    List<TrackedRepo> findAllByTeamId(Long teamId);

    @Modifying(clearAutomatically = true)
    @Query("""
            update TrackedRepo r set r.lastProcessedSha = :head
            where r.id = :id
              and ((:base is null and r.lastProcessedSha is null) or r.lastProcessedSha = :base)
            """)
    int advanceCursor(@Param("id") Long id, @Param("base") String base, @Param("head") String head);
}
