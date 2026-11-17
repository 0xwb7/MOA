package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DigestRepository extends JpaRepository<Digest, Long> {

    List<Digest> findAllByTeamIdAndStatus(Long teamId, DigestStatus status);
}
