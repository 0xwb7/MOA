package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByName(String name);
    Optional<Team> findByDiscordChannelId(Long discordChannelId);

    /**
     * 정기 회차(준비·복구) 대상: 활성 레포가 하나 이상인 팀.
     * 레포 없이 팀만 등록했거나 레포를 모두 해제한 채널에 "변경 사항이 없어요"가 쌓이지 않게 한다.
     */
    @Query("select t from Team t where exists (select 1 from TrackedRepo r where r.team = t and r.active = true) order by t.id")
    List<Team> findAllWithTrackedRepo();

    /**
     * 발송 단계 대상: 활성 레포가 있는 팀 + 이 회차가 이미 만들어진 팀.
     * 준비(8:30) 뒤 발송(9:00) 전에 마지막 레포를 해제해도, 준비된 회차와 거기 담긴 배치는 그대로 발송한다.
     */
    @Query("""
            select t from Team t
            where exists (select 1 from TrackedRepo r where r.team = t and r.active = true)
               or exists (select 1 from Digest d where d.team = t and d.scheduledFor = :cycle)
            order by t.id
            """)
    List<Team> findAllForSendCycle(@Param("cycle") Instant cycle);
}
