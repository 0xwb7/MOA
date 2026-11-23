package discord.moa.discordmoa.discord.sender;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChannelPermissionsTest {

    @Test
    void 권한이_모두_있으면_빠진_권한이_없다() {
        TextChannel channel = channel(TextChannel.class, true, true);

        assertThat(ChannelPermissions.missing(channel)).isEmpty();
    }

    @Test
    void 비공개_채널처럼_보기_권한이_없으면_빠진_권한으로_알려준다() {
        TextChannel channel = channel(TextChannel.class, false, false);

        assertThat(ChannelPermissions.missing(channel))
                .containsExactly(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
        assertThat(ChannelPermissions.describeMissing(channel, ChannelPermissions.missing(channel)))
                .isEqualTo("봇에게 #team-1 채널의 " + Permission.VIEW_CHANNEL.getName() + ", "
                        + Permission.MESSAGE_SEND.getName() + " 권한이 없어요.");
    }

    @Test
    void 메시지_기록_보기는_요구하지_않는다() {
        assertThat(ChannelPermissions.required(mock(TextChannel.class)))
                .containsExactly(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
                .doesNotContain(Permission.MESSAGE_HISTORY);
    }

    @Test
    void 스레드는_스레드에서_보내기_권한을_본다() {
        assertThat(ChannelPermissions.required(mock(ThreadChannel.class)))
                .containsExactly(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND_IN_THREADS);
    }

    private static <T extends GuildMessageChannel> T channel(Class<T> type, boolean view, boolean send) {
        T channel = mock(type);
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        given(channel.getName()).willReturn("team-1");
        given(channel.getGuild()).willReturn(guild);
        given(guild.getSelfMember()).willReturn(self);
        given(self.hasPermission(channel, Permission.VIEW_CHANNEL)).willReturn(view);
        given(self.hasPermission(channel, Permission.MESSAGE_SEND)).willReturn(send);
        return channel;
    }
}
