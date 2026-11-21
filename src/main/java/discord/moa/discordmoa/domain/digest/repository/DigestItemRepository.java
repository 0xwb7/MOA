package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DigestItemRepository extends JpaRepository<DigestItem, Long> {

    // id 순서 = 만든 순서 = 메시지에 보여줄 순서
    List<DigestItem> findAllByDigestIdOrderByIdAsc(Long digestId);
}
