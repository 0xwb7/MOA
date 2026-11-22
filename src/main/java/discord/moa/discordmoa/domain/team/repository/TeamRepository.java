package discord.moa.discordmoa.domain.team.repository;

import discord.moa.discordmoa.domain.team.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByName(String name);
    Optional<Team> findByDiscordChannelId(Long discordChannelId);

    /**
     * 정기 회차(준비·발송·복구) 대상: 등록된 레포가 하나 이상인 팀.
     * 레포 없이 팀만 등록한 채널에 "변경 사항이 없어요"가 쌓이지 않게 한다.
     */
    @Query("select t from Team t where exists (select 1 from TrackedRepo r where r.team = t) order by t.id")
    List<Team> findAllWithTrackedRepo();
}
