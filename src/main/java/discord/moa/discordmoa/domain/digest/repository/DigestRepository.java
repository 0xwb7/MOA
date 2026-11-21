package discord.moa.discordmoa.domain.digest.repository;

import discord.moa.discordmoa.domain.digest.entity.CycleKind;
import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DigestRepository extends JpaRepository<Digest, Long> {

    Optional<Digest> findByTeamIdAndScheduledFor(Long teamId, Instant scheduledFor);

    Optional<Digest> findFirstByTeamIdAndCycleKindAndScheduledForLessThanEqualOrderByScheduledForDesc(
            Long teamId, CycleKind cycleKind, Instant scheduledFor);

    @EntityGraph(attributePaths = "team")
    List<Digest> findAllByStatusInAndScheduledForLessThanEqualOrderByScheduledForAsc(
            Collection<DigestStatus> statuses, Instant scheduledFor);

    /**
     * PREPARING일 때만 READY로 바꾼다. 0이면 다른 쪽(마감 fallback, 발송 시점 즉석 fallback)이 먼저 처리한 것이다.
     * UPDATE가 잡은 행 잠금은 트랜잭션이 끝날 때까지 유지돼서, 경쟁하는 쪽은 커밋 후의 상태를 보게 된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Digest d set d.status = :ready, d.readyAt = :now where d.id = :id and d.status = :preparing")
    int markReadyIfPreparing(@Param("id") Long id, @Param("preparing") DigestStatus preparing,
                             @Param("ready") DigestStatus ready, @Param("now") Instant now);

    /**
     * READY이고 아직 아무도 보내기 시작하지 않았을 때만 발송을 선점한다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Digest d set d.sendStartedAt = :now where d.id = :id and d.status = :ready and d.sendStartedAt is null")
    int claimSend(@Param("id") Long id, @Param("ready") DigestStatus ready, @Param("now") Instant now);

    /**
     * 재시작 복구: 발송 도중 서버가 멈춰 선점만 남은 READY 회차를 다시 보낼 수 있게 한다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Digest d set d.sendStartedAt = null where d.id = :id and d.status = :ready")
    int releaseSendClaim(@Param("id") Long id, @Param("ready") DigestStatus ready);
}
