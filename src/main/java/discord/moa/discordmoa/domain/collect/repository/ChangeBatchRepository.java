package discord.moa.discordmoa.domain.collect.repository;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeBatchRepository extends JpaRepository<ChangeBatch, Long> {

    @EntityGraph(attributePaths = "trackedRepo")
    List<ChangeBatch> findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(Long teamId, ChangeBatchStatus status);
}
