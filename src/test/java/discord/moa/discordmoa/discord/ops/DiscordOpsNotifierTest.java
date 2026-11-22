package discord.moa.discordmoa.discord.ops;

import discord.moa.discordmoa.domain.ops.LoggingOpsNotifier;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DiscordOpsNotifierTest {

    private static final OpsEvent EVENT = new OpsEvent(OpsEvent.Type.SEND_FAILED, "1팀",
            Instant.parse("2026-11-27T00:00:00Z"), "Discord 오류");

    private final LoggingOpsNotifier logging = mock(LoggingOpsNotifier.class);
    private final JDA jda = mock(JDA.class);

    @Test
    void 채널_설정이_없으면_로그만_남기고_예외가_없다() {
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "", "");

        assertThatCode(() -> notifier.notify(EVENT)).doesNotThrowAnyException();

        verify(logging).notify(EVENT);
        verifyNoInteractions(jda);
        assertThat(notifier.sendTest("운영자").join().status()).isEqualTo(OpsTestResult.Status.NOT_CONFIGURED);
    }

    @Test
    void 채널을_찾을_수_없으면_로그만_남긴다() {
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        assertThatCode(() -> notifier.notify(EVENT)).doesNotThrowAnyException();

        verify(logging).notify(EVENT);
        assertThat(notifier.sendTest("운영자").join().status()).isEqualTo(OpsTestResult.Status.CHANNEL_NOT_FOUND);
    }

    @Test
    void 권한이_없으면_보내지_않고_로그만_남긴다() {
        GuildMessageChannel channel = channel(true, false);
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        notifier.notify(EVENT);

        verify(channel, never()).sendMessage(any(MessageCreateData.class));
        verify(logging).notify(EVENT);
        OpsTestResult result = notifier.sendTest("운영자").join();
        assertThat(result.status()).isEqualTo(OpsTestResult.Status.NO_PERMISSION);
        assertThat(result.detail()).contains(Permission.MESSAGE_SEND.getName());
    }

    @Test
    void 운영_채널로_비동기_전송하고_로그도_남긴다() {
        GuildMessageChannel channel = channel(true, true);
        MessageCreateAction action = mock(MessageCreateAction.class);
        ArgumentCaptor<MessageCreateData> sent = ArgumentCaptor.forClass(MessageCreateData.class);
        given(channel.sendMessage(sent.capture())).willReturn(action);
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "555");

        notifier.notify(EVENT);

        verify(logging).notify(EVENT);
        verify(action).queue(any(), any());   // complete()로 기다리지 않는다
        assertThat(sent.getValue().getContent()).startsWith("🔴 **발송 실패** <@&555>");
        assertThat(sent.getValue().getMentionedRoles()).containsExactly("555");
    }

    @Test
    void 전송이_실패해도_예외가_밖으로_나가지_않는다() {
        GuildMessageChannel channel = channel(true, true);
        given(channel.sendMessage(any(MessageCreateData.class))).willThrow(new IllegalStateException("JDA 오류"));
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        assertThatCode(() -> notifier.notify(EVENT)).doesNotThrowAnyException();
        verify(logging).notify(EVENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 비동기_전송_실패_콜백도_예외를_던지지_않는다() {
        GuildMessageChannel channel = channel(true, true);
        MessageCreateAction action = mock(MessageCreateAction.class);
        given(channel.sendMessage(any(MessageCreateData.class))).willReturn(action);
        willAnswer(inv -> {
            ((Consumer<Throwable>) inv.getArgument(1)).accept(new RuntimeException("rate limited"));
            return null;
        }).given(action).queue(any(), any());
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        assertThatCode(() -> notifier.notify(EVENT)).doesNotThrowAnyException();
    }

    @Test
    void 로그_기록이_실패해도_Discord_전송은_시도하고_예외가_없다() {
        GuildMessageChannel channel = channel(true, true);
        given(channel.sendMessage(any(MessageCreateData.class))).willReturn(mock(MessageCreateAction.class));
        willThrow(new IllegalStateException("로그 오류")).given(logging).notify(any());
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        assertThatCode(() -> notifier.notify(EVENT)).doesNotThrowAnyException();
        verify(channel).sendMessage(any(MessageCreateData.class));
    }

    @Test
    void 테스트_알림_결과를_돌려준다() {
        GuildMessageChannel channel = channel(true, true);
        given(channel.getName()).willReturn("ops");
        MessageCreateAction ok = mock(MessageCreateAction.class);
        given(ok.submit()).willReturn(CompletableFuture.completedFuture(mock(Message.class)));
        MessageCreateAction broken = mock(MessageCreateAction.class);
        given(broken.submit()).willReturn(CompletableFuture.failedFuture(new RuntimeException("Missing Access")));
        given(channel.sendMessage(any(MessageCreateData.class))).willReturn(ok, broken);
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "100", "");

        OpsTestResult success = notifier.sendTest("운영자").join();
        OpsTestResult failure = notifier.sendTest("운영자").join();

        assertThat(success.status()).isEqualTo(OpsTestResult.Status.SENT);
        assertThat(success.describe()).contains("#ops");
        assertThat(failure.status()).isEqualTo(OpsTestResult.Status.FAILED);
        assertThat(failure.detail()).contains("Missing Access");
    }

    @Test
    void 시작하면_시작_알림을_보낸다() {
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "", "");

        notifier.onStarted();

        ArgumentCaptor<OpsEvent> event = ArgumentCaptor.forClass(OpsEvent.class);
        verify(logging).notify(event.capture());
        assertThat(event.getValue().type()).isEqualTo(OpsEvent.Type.STARTED);
        assertThat(event.getValue().type().severity()).isEqualTo(OpsEvent.Severity.INFO);
        assertThat(event.getValue().detail()).isEqualTo("MOA가 시작됐어요");
    }

    @Test
    void 채널_ID가_숫자가_아니면_설정_없음으로_본다() {
        DiscordOpsNotifier notifier = new DiscordOpsNotifier(jda, logging, "ops-channel", "");

        assertThat(notifier.sendTest("운영자").join().status()).isEqualTo(OpsTestResult.Status.NOT_CONFIGURED);
    }

    @Test
    void 스프링에서는_Discord_구현체가_기본_OpsNotifier로_주입된다() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(JDA.class, () -> jda)
                .withBean(LoggingOpsNotifier.class)
                .withBean(DiscordOpsNotifier.class)
                .withPropertyValues("discord.ops-channel-id=", "discord.ops-role-id=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(discord.moa.discordmoa.domain.ops.OpsNotifier.class))
                            .isInstanceOf(DiscordOpsNotifier.class);
                });
    }

    private GuildMessageChannel channel(boolean view, boolean send) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        Guild guild = mock(Guild.class);
        SelfMember self = mock(SelfMember.class);
        given(jda.getChannelById(GuildMessageChannel.class, 100L)).willReturn(channel);
        given(channel.getName()).willReturn("ops");
        given(channel.getGuild()).willReturn(guild);
        given(guild.getSelfMember()).willReturn(self);
        given(self.hasPermission(channel, Permission.VIEW_CHANNEL)).willReturn(view);
        given(self.hasPermission(channel, Permission.MESSAGE_SEND)).willReturn(send);
        return channel;
    }
}
