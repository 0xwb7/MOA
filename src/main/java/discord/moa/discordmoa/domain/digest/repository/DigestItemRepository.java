package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DigestItemRepository extends JpaRepository<DigestItem, Long> {
}
