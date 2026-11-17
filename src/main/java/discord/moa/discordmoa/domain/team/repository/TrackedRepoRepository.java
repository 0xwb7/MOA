package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrackedRepoRepository extends JpaRepository<TrackedRepo, Long> {

    boolean existsByGithubRepoId(Long githubRepoId);

    List<TrackedRepo> findAllByTeamId(Long teamId);

    @Modifying(clearAutomatically = true)
    @Query("""
            update TrackedRepo r set r.lastProcessedSha = :head
            where r.id = :id
              and ((:base is null and r.lastProcessedSha is null) or r.lastProcessedSha = :base)
            """)
    int advanceCursor(@Param("id") Long id, @Param("base") String base, @Param("head") String head);
}
