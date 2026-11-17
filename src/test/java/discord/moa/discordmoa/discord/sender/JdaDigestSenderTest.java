package discord.moa.discordmoa.discord.sender;

import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponentUnion;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class JdaDigestSenderTest {

    private static final DigestMessage FIRST = new DigestMessage(7L, List.of(
            DigestBlock.textOnly("<@7> 헤더"),
            DigestBlock.item(11L, "📦 owner/a (커밋 1개)"),
            DigestBlock.item(12L, "📦 owner/b (커밋 1개)")));

    @Test
    void Components_V2로_항목마다_텍스트_바로_아래에_버튼_두_개를_붙인다() {
        MessageCreateData data = JdaDigestSender.toMessageData(FIRST);

        assertThat(data.isUsingComponentsV2()).isTrue();
        assertThat(data.getContent()).isEmpty();
        assertThat(data.getComponents()).extracting(Component::getType).containsExactly(
                Component.Type.TEXT_DISPLAY,
                Component.Type.SEPARATOR, Component.Type.TEXT_DISPLAY, Component.Type.ACTION_ROW,
                Component.Type.SEPARATOR, Component.Type.TEXT_DISPLAY, Component.Type.ACTION_ROW);

        List<MessageTopLevelComponentUnion> components = data.getComponents();
        assertThat(components.get(2).asTextDisplay().getContent()).isEqualTo("📦 owner/a (커밋 1개)");
        assertThat(components.get(3).asActionRow().getButtons())
                .extracting(Button::getCustomId, Button::getLabel)
                .containsExactly(
                        tuple("confirm:11", "확인했어요"),
                        tuple("ask:11", "개발자에게 질문하기"));
        assertThat(components.get(6).asActionRow().getButtons())
                .extracting(Button::getCustomId).containsExactly("confirm:12", "ask:12");
    }

    @Test
    void PM만_멘션을_허용하고_나머지_멘션은_막는다() {
        MessageCreateData withMention = JdaDigestSender.toMessageData(FIRST);
        MessageCreateData withoutMention = JdaDigestSender.toMessageData(
                new DigestMessage(null, List.of(DigestBlock.item(13L, "@everyone 커밋"))));

        assertThat(withMention.getAllowedMentions()).isEmpty();
        assertThat(withMention.getMentionedUsers()).containsExactly("7");
        assertThat(withoutMention.getAllowedMentions()).isEmpty();
        assertThat(withoutMention.getMentionedUsers()).isEmpty();
    }

    @Test
    void 채널을_찾을_수_없으면_원인을_담아_실패한다() {
        JDA jda = mock(JDA.class);
        JdaDigestSender sender = new JdaDigestSender(jda);

        assertThatThrownBy(() -> sender.send(100L, List.of(FIRST)))
                .isInstanceOf(DigestSendException.class)
                .hasMessageContaining("팀 채널을 찾을 수 없어요 (channelId=100)");
    }

    @Test
    void 메시지_보내기_권한이_없으면_보내지_않고_원인을_담아_실패한다() {
        GuildMessageChannel channel = channelWithPermissions(true, false);
        JdaDigestSender sender = new JdaDigestSender(jdaReturning(channel));

        assertThatThrownBy(() -> sender.send(100L, List.of(FIRST)))
                .isInstanceOf(DigestSendException.class)
                .hasMessage("봇에게 #team-1 채널의 " + Permission.MESSAGE_SEND.getName() + " 권한이 없어요.");
    }

    @Test
    void 두_번째_메시지에서_실패하면_첫_메시지_ID를_담아_실패한다() {
        GuildMessageChannel channel = channelWithPermissions(true, true);
        Message sent = mock(Message.class);
        given(sent.getIdLong()).willReturn(9001L);
        MessageCreateAction ok = mock(MessageCreateAction.class);
        given(ok.complete()).willReturn(sent);
        MessageCreateAction broken = mock(MessageCreateAction.class);
        given(broken.complete()).willThrow(new IllegalStateException("network down"));
        given(channel.sendMessage(any(MessageCreateData.class))).willReturn(ok, broken);
        JdaDigestSender sender = new JdaDigestSender(jdaReturning(channel));

        assertThatThrownBy(() -> sender.send(100L, List.of(FIRST, FIRST)))
                .isInstanceOfSatisfying(DigestSendException.class,
                        e -> assertThat(e.getSentMessageIds()).containsExactly(9001L))
                .hasMessageContaining("network down");
    }

    private static GuildMessageChannel channelWithPermissions(boolean view, boolean send) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        given(channel.getName()).willReturn("team-1");
        given(channel.getGuild()).willReturn(guild);
        given(guild.getSelfMember()).willReturn(self);
        given(self.hasPermission(channel, Permission.VIEW_CHANNEL)).willReturn(view);
        given(self.hasPermission(channel, Permission.MESSAGE_SEND)).willReturn(send);
        return channel;
    }

    private static JDA jdaReturning(GuildMessageChannel channel) {
        JDA jda = mock(JDA.class);
        given(jda.getChannelById(GuildMessageChannel.class, 100L)).willReturn(channel);
        return jda;
    }
}
