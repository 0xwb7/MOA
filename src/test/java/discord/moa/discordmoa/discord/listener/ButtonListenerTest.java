package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.service.ConfirmCheck;
import discord.moa.discordmoa.domain.digest.service.DigestConfirmService;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ButtonListenerTest {

    private static final long ITEM_ID = 11L;
    private static final long PM_ID = 7L;
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-27T00:12:00Z");   // 09:12 KST

    private final DigestConfirmService confirmService = mock(DigestConfirmService.class);
    private final ButtonListener listener = new ButtonListener(confirmService);

    // ---- 확인했어요 ----

    @Test
    void PM이_누르면_기록한_뒤_그_버튼만_확인_완료로_바꾼다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMABLE, null));
        given(confirmService.confirm(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT));

        listener.onButtonInteraction(click.event);

        InOrder order = inOrder(click.event, click.deferEdit, confirmService);
        order.verify(click.event).deferEdit();
        order.verify(click.deferEdit).queue();
        order.verify(confirmService).confirm(ITEM_ID, PM_ID);
        order.verify(click.event).editButton(any());

        ArgumentCaptor<Button> edited = ArgumentCaptor.forClass(Button.class);
        verify(click.event).editButton(edited.capture());
        assertThat(edited.getValue().getCustomId()).isEqualTo("confirm:11");
        assertThat(edited.getValue().getLabel()).isEqualTo("✅ 확인 완료");
        assertThat(edited.getValue().isDisabled()).isTrue();
        verify(click.event, never()).reply(anyString());
    }

    @Test
    void PM이_아니면_본인에게만_안내하고_기록하지_않는다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.NOT_PM, null));

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("이 팀의 PM만 확인할 수 있어요.");
        verify(confirmService, never()).confirm(anyLong(), anyLong());
        verify(click.event, never()).deferEdit();
    }

    @Test
    void 이미_확인된_항목이면_확인_시각을_알려준다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID))
                .willReturn(new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT));

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("이미 확인했어요 (11-27 09:12)");
        verify(confirmService, never()).confirm(anyLong(), anyLong());
    }

    @Test
    void 항목을_찾을_수_없으면_안내한다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.NOT_FOUND, null));

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("찾을 수 없는 항목이에요.");
    }

    @Test
    void 확인과_기록_사이에_다른_클릭이_먼저_기록했으면_버튼을_바꾸지_않고_안내한다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMABLE, null));
        given(confirmService.confirm(ITEM_ID, PM_ID))
                .willReturn(new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT));

        listener.onButtonInteraction(click.event);

        verify(click.hook).sendMessage("이미 확인했어요 (11-27 09:12)");
        verify(click.followUp).setEphemeral(true);
        verify(click.event, never()).editButton(any());
    }

    @Test
    void 메시지_수정이_바로_실패해도_예외가_없고_기록은_유지된다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMABLE, null));
        given(confirmService.confirm(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT));
        given(click.event.editButton(any())).willThrow(new IllegalStateException("message not cached"));

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();
        verify(confirmService).confirm(ITEM_ID, PM_ID);   // 기록은 이미 끝났고 되돌리지 않는다
    }

    @Test
    @SuppressWarnings("unchecked")
    void 메시지_수정이_비동기로_실패해도_예외가_없다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMABLE, null));
        given(confirmService.confirm(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT));
        willAnswer(inv -> {
            ((Consumer<Throwable>) inv.getArgument(1)).accept(new RuntimeException("Unknown Message"));
            return null;
        }).given(click.editAction).queue(any(), any());

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();
        verify(confirmService).confirm(ITEM_ID, PM_ID);
    }

    @Test
    void 확인_중_DB_오류가_나면_본인에게만_안내한다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();
        click.verifyEphemeralReply(ButtonListener.UNEXPECTED_ERROR);
    }

    // ---- 질문하기, 버튼 ID ----

    @Test
    void 질문하기_버튼은_준비_중이라고_본인에게만_알린다() {
        Click click = click("ask:11");

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply(ButtonListener.NOT_READY);
        verifyNoInteractions(confirmService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"confirm", "confirm:", ":11", "confirm:abc", "confirm:1:2", "confirm:-1", "confirm:0",
            "confirm: 11", "confirm:99999999999999999999", "unknown:11"})
    void 형식이_다르거나_모르는_버튼은_예외_없이_무시한다(String componentId) {
        Click click = click(componentId);

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();
        verify(click.event, never()).reply(anyString());
        verifyNoInteractions(confirmService);
    }

    @Test
    void 버튼_ID를_action과_항목_ID로_나눈다() {
        assertThat(ButtonListener.ButtonId.parse("confirm:42"))
                .contains(new ButtonListener.ButtonId("confirm", 42L));
        assertThat(ButtonListener.ButtonId.parse("confirm:1:2")).isEmpty();
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Click click(String componentId) {
        Click click = new Click();
        given(click.event.getComponentId()).willReturn(componentId);
        User user = mock(User.class);
        given(user.getIdLong()).willReturn(PM_ID);
        given(click.event.getUser()).willReturn(user);
        given(click.event.reply(anyString())).willReturn(click.reply);
        given(click.reply.setEphemeral(true)).willReturn(click.reply);
        given(click.event.deferEdit()).willReturn(click.deferEdit);
        given(click.event.getHook()).willReturn(click.hook);
        given(click.hook.sendMessage(anyString())).willReturn(click.followUp);
        given(click.followUp.setEphemeral(true)).willReturn(click.followUp);
        given(click.event.getButton()).willReturn(Button.success("confirm:" + ITEM_ID, "확인했어요"));
        given(click.event.editButton(any())).willReturn(click.editAction);
        return click;
    }

    @SuppressWarnings("unchecked")
    private static final class Click {
        final ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        final ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        final MessageEditCallbackAction deferEdit = mock(MessageEditCallbackAction.class);
        final InteractionHook hook = mock(InteractionHook.class);
        final WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message> followUp = mock(WebhookMessageCreateAction.class);
        final RestAction<Void> editAction = mock(RestAction.class);

        void verifyEphemeralReply(String message) {
            verify(event).reply(message);
            verify(reply).setEphemeral(true);
            verify(reply).queue();
            verify(event, never()).deferEdit();
        }
    }
}
