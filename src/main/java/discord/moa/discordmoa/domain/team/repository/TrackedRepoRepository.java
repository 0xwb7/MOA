package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackedRepoRepository extends JpaRepository<TrackedRepo, Long> {

    boolean existsByGithubRepoId(Long githubRepoId);
}
