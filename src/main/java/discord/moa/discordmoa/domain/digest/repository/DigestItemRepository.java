package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * 같은 팀이 이전 회차에 PM에게 보고한 기능 항목: 발송된(SENT) LLM 회차의, 지정한 상태(READY·PARTIAL)인 항목을 최신순으로.
     */
    @Query("""
            select new discord.moa.discordmoa.domain.digest.repository.PreviousItemRow(i.id, d.scheduledFor, i.itemStatus, i.title)
            from DigestItem i join i.digest d
            where d.team.id = :teamId
              and d.status = :sent
              and d.type = :llm
              and i.itemStatus in :statuses
              and d.sentAt >= :since
            order by d.sentAt desc, i.id desc
            """)
    List<PreviousItemRow> findPreviousItems(@Param("teamId") Long teamId, @Param("sent") DigestStatus sent,
                                            @Param("llm") DigestType llm, @Param("statuses") Collection<String> statuses,
                                            @Param("since") Instant since, Pageable pageable);

    // /회차상태 "PM 확인 2/3": 실제로 전달된 항목 기준
    long countByDigestIdAndDiscordMessageIdIsNotNull(Long digestId);

    long countByDigestIdAndDiscordMessageIdIsNotNullAndConfirmedAtIsNotNull(Long digestId);
}
