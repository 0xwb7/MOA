package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.guide.service.UsageGuide;
import discord.moa.discordmoa.domain.guide.service.UsageGuideService;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UsageCommandTest {

    private final UsageGuideService usageGuideService = mock(UsageGuideService.class);
    private final SlashCommandListener listener = new SlashCommandListener(List.of(
            new GeneralCommandHandler(mock(AccountLinkService.class), usageGuideService, mock(ExecutorService.class))));

    @Test
    @SuppressWarnings("unchecked")
    void 사용법은_실행한_채널과_사람_기준으로_본인에게만_보이게_멘션_없이_답한다() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        given(event.getName()).willReturn("사용법");
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        given(channel.getIdLong()).willReturn(100L);
        given(event.getChannel()).willReturn(channel);
        User user = mock(User.class);
        given(user.getIdLong()).willReturn(8L);
        given(event.getUser()).willReturn(user);
        given(event.reply(anyString())).willReturn(reply);
        given(usageGuideService.build(100L, 8L)).willReturn(new UsageGuide(null, false, "alice", 0, null,
                Instant.parse("2026-11-25T05:00:00Z"), ZoneId.of("Asia/Seoul"), false, false, null));

        listener.onSlashCommandInteraction(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event).reply(text.capture());
        assertThat(text.getValue()).startsWith(UsageGuideMessage.INTRO).contains("✅ GitHub 연동: alice");
        verify(reply).setEphemeral(true);
        ArgumentCaptor<Collection<net.dv8tion.jda.api.entities.Message.MentionType>> mentions =
                ArgumentCaptor.forClass(Collection.class);
        verify(reply).setAllowedMentions(mentions.capture());
        assertThat(mentions.getValue()).isEmpty();
        verify(reply).queue();
    }
}
