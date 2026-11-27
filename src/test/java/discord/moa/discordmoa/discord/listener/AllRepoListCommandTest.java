package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService.TeamRepos;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AllRepoListCommandTest {

    private final TrackedRepoService trackedRepoService = mock(TrackedRepoService.class);
    private final SlashCommandListener listener = new SlashCommandListener(List.of(
            new TeamCommandHandler(mock(TeamService.class), trackedRepoService, mock(ExecutorService.class))));

    private final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
    private final ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
    private final Member member = mock(Member.class);

    @Test
    void 운영진이_아니면_조회하지_않고_본인에게만_거절_안내를_보낸다() {
        givenCommand(false);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(TeamCommandHandler.ADMIN_ONLY);
        verify(reply).setEphemeral(true);
        verify(trackedRepoService, never()).listAllActiveByTeam();
    }

    @Test
    void 서버_밖이라_멤버가_없으면_운영진이_아니다() {
        givenCommand(false);
        given(event.getMember()).willReturn(null);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(TeamCommandHandler.ADMIN_ONLY);
        verify(trackedRepoService, never()).listAllActiveByTeam();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 운영진이면_첫_응답과_이어지는_메시지를_모두_본인에게만_보낸다() {
        givenCommand(true);
        List<TeamRepos> teams = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new TeamRepos(i + "팀", 100L + i, IntStream.rangeClosed(1, 15)
                        .mapToObj(r -> "growthon-team-" + i + "/service-repository-" + r).toList()))
                .toList();
        given(trackedRepoService.listAllActiveByTeam()).willReturn(teams);
        List<String> expected = AllRepoListMessage.format(teams);
        assertThat(expected).hasSizeGreaterThan(2);

        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageCreateAction<Message> followup = mock(WebhookMessageCreateAction.class, RETURNS_SELF);
        given(event.getHook()).willReturn(hook);
        given(hook.sendMessage(anyString())).willReturn(followup);

        listener.onSlashCommandInteraction(event);

        verify(event).reply(expected.get(0));
        verify(reply).setEphemeral(true);
        verify(reply).setAllowedMentions(List.of());
        // 이어지는 메시지는 앞 메시지가 끝난 뒤에 순서대로 보낸다
        ArgumentCaptor<Function> next = ArgumentCaptor.forClass(Function.class);
        verify(reply, times(expected.size() - 1)).flatMap(next.capture());
        verify(hook, never()).sendMessage(anyString());
        InOrder order = inOrder(hook);
        for (int i = 1; i < expected.size(); i++) {
            next.getAllValues().get(i - 1).apply(null);
            order.verify(hook).sendMessage(expected.get(i));
        }
        verify(followup, times(expected.size() - 1)).setEphemeral(true);
        verify(followup, times(expected.size() - 1)).setAllowedMentions(List.of());
        verify(reply).queue();
    }

    private void givenCommand(boolean admin) {
        given(event.getName()).willReturn("레포전체목록");
        given(event.getMember()).willReturn(member);
        given(member.hasPermission(Permission.MANAGE_SERVER)).willReturn(admin);
        given(event.reply(anyString())).willReturn(reply);
    }
}
