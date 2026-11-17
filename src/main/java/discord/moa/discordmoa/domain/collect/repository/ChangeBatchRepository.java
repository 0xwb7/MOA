package discord.moa.discordmoa.domain.collect.repository;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeBatchRepository extends JpaRepository<ChangeBatch, Long> {
}
