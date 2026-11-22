package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DigestItemRepository extends JpaRepository<DigestItem, Long> {

    // id 순서 = 만든 순서 = 메시지에 보여줄 순서
    List<DigestItem> findAllByDigestIdOrderByIdAsc(Long digestId);

    @Query("select i from DigestItem i join fetch i.digest d join fetch d.team where i.id = :id")
    Optional<DigestItem> findWithTeamById(@Param("id") Long id);

    /**
     * 아직 확인되지 않았을 때만 확인을 기록한다. 연타나 동시 클릭에도 한 번만 1을 돌려준다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DigestItem i set i.confirmedAt = :now, i.confirmedBy = :userId where i.id = :id and i.confirmedAt is null")
    int confirm(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    // /회차상태 "PM 확인 2/3": 실제로 전달된 항목 기준
    long countByDigestIdAndDiscordMessageIdIsNotNull(Long digestId);

    long countByDigestIdAndDiscordMessageIdIsNotNullAndConfirmedAtIsNotNull(Long digestId);
}
