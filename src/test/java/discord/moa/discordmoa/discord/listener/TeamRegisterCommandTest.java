package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.team.service.TeamRegistration;
import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * /팀등록: 봇이 발송하거나 질문 답글을 달 수 없는 채널이면 등록하지 않고, 신규와 수정을 응답에서 구분한다.
 */
class TeamRegisterCommandTest {

    private static final long CHANNEL_ID = 100L;

    private final TeamService teamService = mock(TeamService.class);
    private final SlashCommandListener listener = new SlashCommandListener(List.of(
            new TeamCommandHandler(teamService, mock(TrackedRepoService.class), mock(ExecutorService.class))));

    private final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
    private final Guild guild = mock(Guild.class);
    private final SelfMember self = mock(SelfMember.class);
    private final TextChannel channel = mock(TextChannel.class);
    private final ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);

    @Test
    void 봇에게_권한이_없는_채널이면_등록하지_않고_빠진_권한을_알려준다() {
        givenCommand(true);
        givenPermissions(false, false, false);

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).contains("봇에게 #team-1 채널의", Permission.VIEW_CHANNEL.getName(),
                Permission.MESSAGE_SEND.getName(), Permission.MESSAGE_HISTORY.getName(), "다시 /팀등록");
        verify(teamService, never()).register(anyString(), anyLong(), anyLong());
    }

    @Test
    void 메시지_보내기만_없어도_등록하지_않는다() {
        givenCommand(true);
        givenPermissions(true, false, true);

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).contains(Permission.MESSAGE_SEND.getName())
                .doesNotContain(Permission.VIEW_CHANNEL.getName(), Permission.MESSAGE_HISTORY.getName());
        verify(teamService, never()).register(anyString(), anyLong(), anyLong());
    }

    @Test
    void 메시지_기록_보기가_없으면_질문_답글에_필요하다고_알리고_등록하지_않는다() {
        givenCommand(true);
        givenPermissions(true, true, false);

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).isEqualTo("⚠️ 봇에게 #team-1 채널의 " + Permission.MESSAGE_HISTORY.getName() + " 권한이 없어요.\n"
                + Permission.MESSAGE_HISTORY.getName() + " 권한은 개발자 질문을 요약 메시지의 답글로 달 때 필요해요.\n"
                + "채널 설정 → 권한에서 MOA 봇(또는 봇 역할)에 이 권한을 준 뒤 다시 /팀등록 해 주세요.");
        verify(teamService, never()).register(anyString(), anyLong(), anyLong());
    }

    @Test
    void 봇이_채널을_찾지_못하면_등록하지_않는다() {
        givenCommand(false);

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).contains("봇이 이 채널을 찾을 수 없어요");
        verify(teamService, never()).register(anyString(), anyLong(), anyLong());
    }

    @Test
    void 신규_등록이면_등록_완료로_응답한다() {
        givenCommand(true);
        givenPermissions(true, true, true);
        given(teamService.register("1팀", CHANNEL_ID, 8L))
                .willReturn(new TeamRegistration("1팀", true, 8L, null, CHANNEL_ID, null));

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).isEqualTo("✅ '1팀' 등록이 완료됐어요.");
    }

    @Test
    void 기존_팀_수정이면_이전_PM과_새_PM을_보여준다() {
        givenCommand(true);
        givenPermissions(true, true, true);
        given(teamService.register("1팀", CHANNEL_ID, 8L))
                .willReturn(new TeamRegistration("1팀", false, 8L, 7L, CHANNEL_ID, CHANNEL_ID));

        listener.onSlashCommandInteraction(event);

        assertThat(replyText()).isEqualTo("✅ '1팀' 정보가 수정됐어요 (PM: <@7> → <@8>)");
    }

    @Test
    void 수정_응답은_PM이_그대로면_그대로라고_하고_채널이_바뀌면_함께_보여준다() {
        assertThat(TeamCommandHandler.describeTeamRegistration(
                new TeamRegistration("1팀", false, 7L, 7L, 101L, 100L)))
                .isEqualTo("✅ '1팀' 정보가 수정됐어요 (PM: <@7> 그대로)\n채널: <#100> → <#101>");
    }

    private void givenCommand(boolean channelCached) {
        given(event.getName()).willReturn("팀등록");
        OptionMapping name = mock(OptionMapping.class);
        given(name.getAsString()).willReturn("1팀");
        OptionMapping channelOption = mock(OptionMapping.class);
        GuildChannelUnion union = mock(GuildChannelUnion.class);
        given(union.getIdLong()).willReturn(CHANNEL_ID);
        given(channelOption.getAsChannel()).willReturn(union);
        OptionMapping pm = mock(OptionMapping.class);
        User pmUser = mock(User.class);
        given(pmUser.getIdLong()).willReturn(8L);
        given(pm.getAsUser()).willReturn(pmUser);
        given(event.getOption("name")).willReturn(name);
        given(event.getOption("channel")).willReturn(channelOption);
        given(event.getOption("pm")).willReturn(pm);
        given(event.getGuild()).willReturn(guild);
        given(guild.getTextChannelById(CHANNEL_ID)).willReturn(channelCached ? channel : null);
        given(event.reply(anyString())).willReturn(reply);
    }

    private void givenPermissions(boolean view, boolean send, boolean history) {
        given(channel.getName()).willReturn("team-1");
        given(channel.getGuild()).willReturn(guild);
        given(guild.getSelfMember()).willReturn(self);
        given(self.hasPermission(channel, Permission.VIEW_CHANNEL)).willReturn(view);
        given(self.hasPermission(channel, Permission.MESSAGE_SEND)).willReturn(send);
        given(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).willReturn(history);
    }

    private String replyText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event).reply(text.capture());
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        return text.getValue();
    }
}
