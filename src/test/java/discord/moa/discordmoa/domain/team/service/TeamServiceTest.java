package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TeamServiceTest {

    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TeamService service = new TeamService(teamRepository);

    @Test
    void 처음_등록하면_신규로_돌려준다() {
        given(teamRepository.findByDiscordChannelId(100L)).willReturn(Optional.empty());
        given(teamRepository.findByName("1팀")).willReturn(Optional.empty());

        TeamRegistration registration = service.register("1팀", 100L, 7L);

        assertThat(registration).isEqualTo(new TeamRegistration("1팀", true, 7L, null, 100L, null));
        verify(teamRepository).save(any(Team.class));
    }

    @Test
    void 같은_이름의_팀이면_수정하고_이전_PM과_채널을_돌려준다() {
        Team team = new Team("1팀", 100L, 7L);
        given(teamRepository.findByDiscordChannelId(101L)).willReturn(Optional.empty());
        given(teamRepository.findByName("1팀")).willReturn(Optional.of(team));

        TeamRegistration registration = service.register("1팀", 101L, 8L);

        assertThat(registration).isEqualTo(new TeamRegistration("1팀", false, 8L, 7L, 101L, 100L));
        assertThat(team.getPmDiscordUserId()).isEqualTo(8L);
        assertThat(team.getDiscordChannelId()).isEqualTo(101L);
        verify(teamRepository, never()).save(any());
    }

    @Test
    void 다른_팀이_쓰는_채널이면_거절한다() {
        given(teamRepository.findByDiscordChannelId(100L)).willReturn(Optional.of(new Team("2팀", 100L, 9L)));

        assertThatThrownBy(() -> service.register("1팀", 100L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'2팀'이 사용 중");
    }
}
