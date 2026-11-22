package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.service.ConfirmCheck;
import discord.moa.discordmoa.domain.digest.service.DigestConfirmService;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
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

/**
 * Discord는 상호작용 하나에 응답을 한 번만 받는다. mock은 그 규칙을 모르므로,
 * 이벤트에 대한 응답 메서드 호출 수를 직접 세서 "모든 경로에서 정확히 한 번"을 검증한다.
 */
class ButtonListenerTest {

    private static final long ITEM_ID = 11L;
    private static final long PM_ID = 7L;
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-27T00:12:00Z");   // 09:12 KST

    private final DigestConfirmService confirmService = mock(DigestConfirmService.class);
    private final ButtonListener listener = new ButtonListener(confirmService);

    // ---- 확인했어요 ----

    @Test
    void PM이_누르면_기록한_뒤_버튼_수정을_유일한_응답으로_보낸다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
        givenConfirm(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT);

        listener.onButtonInteraction(click.event);

        InOrder order = inOrder(confirmService, click.event, click.editAction);
        order.verify(confirmService).confirm(ITEM_ID, PM_ID);
        order.verify(click.event).editButton(any());
        order.verify(click.editAction).queue(any(), any());

        ArgumentCaptor<Button> edited = ArgumentCaptor.forClass(Button.class);
        verify(click.event).editButton(edited.capture());
        assertThat(edited.getValue().getCustomId()).isEqualTo("confirm:11");
        assertThat(edited.getValue().getLabel()).isEqualTo("✅ 확인 완료");
        assertThat(edited.getValue().isDisabled()).isTrue();

        verify(click.event, never()).deferEdit();
        assertExactlyOneResponse(click);
    }

    @Test
    void PM이_아니면_본인에게만_안내하고_기록하지_않는다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.NOT_PM, null);

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("이 팀의 PM만 확인할 수 있어요.");
        verify(confirmService, never()).confirm(anyLong(), anyLong());
        assertExactlyOneResponse(click);
    }

    @Test
    void 이미_확인된_항목이면_확인_시각을_알려준다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT);

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("이미 확인했어요 (11-27 09:12)");
        verify(confirmService, never()).confirm(anyLong(), anyLong());
        assertExactlyOneResponse(click);
    }

    @Test
    void 항목을_찾을_수_없으면_안내한다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.NOT_FOUND, null);

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("찾을 수 없는 항목이에요.");
        assertExactlyOneResponse(click);
    }

    @Test
    void 그사이_다른_클릭이_먼저_기록했으면_버튼을_바꾸지_않고_본인에게만_한_번_안내한다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
        givenConfirm(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT);

        listener.onButtonInteraction(click.event);

        click.verifyEphemeralReply("이미 확인했어요 (11-27 09:12)");
        verify(click.event, never()).editButton(any());
        assertExactlyOneResponse(click);
    }

    @Test
    void 기록_중_예외가_나면_오류를_본인에게만_한_번_안내하고_훅은_쓰지_않는다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
        given(confirmService.confirm(ITEM_ID, PM_ID)).willThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();

        click.verifyEphemeralReply(ButtonListener.UNEXPECTED_ERROR);
        verify(click.event, never()).getHook();
        assertExactlyOneResponse(click);
    }

    @Test
    void 확인_전_조회에서_예외가_나도_오류를_한_번_안내한다() {
        Click click = click("confirm:11");
        given(confirmService.check(ITEM_ID, PM_ID)).willThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();

        click.verifyEphemeralReply(ButtonListener.UNEXPECTED_ERROR);
        assertExactlyOneResponse(click);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 버튼_수정_전송이_실패해도_예외가_없고_기록은_되돌리지_않는다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
        givenConfirm(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT);
        willAnswer(inv -> {
            ((Consumer<Throwable>) inv.getArgument(1)).accept(new RuntimeException("10008: Unknown Message"));
            return null;
        }).given(click.editAction).queue(any(), any());

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();

        verify(confirmService).confirm(ITEM_ID, PM_ID);
        verify(click.event, never()).reply(anyString());   // 이미 응답을 시도했으므로 다시 보내지 않는다
        assertExactlyOneResponse(click);
    }

    @Test
    void 버튼_수정을_만들다_실패하면_기록은_두고_본인에게만_한_번_안내한다() {
        Click click = click("confirm:11");
        givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
        givenConfirm(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT);
        given(click.event.editButton(any())).willThrow(new IllegalArgumentException("버튼을 찾을 수 없음"));

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();

        verify(confirmService).confirm(ITEM_ID, PM_ID);
        verify(click.event).reply(ButtonListener.CONFIRMED_BUT_NOT_SHOWN);
        verify(click.reply).setEphemeral(true);
        verify(click.reply).queue();
        // editButton은 응답을 만들기 전에 실패했으므로 실제 응답은 reply 한 번이다
        assertThat(responseCalls(click.event)).containsExactly("editButton", "reply");
    }

    // ---- 모든 경로: 응답은 정확히 한 번 ----

    enum Path {
        NOT_FOUND, NOT_PM, ALREADY_CONFIRMED, CHECK_FAILS,
        CONFIRMED, RACE_ALREADY, RACE_NOT_FOUND, CONFIRM_FAILS, EDIT_SEND_FAILS,
        ASK
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @SuppressWarnings("unchecked")
    void 어떤_경로든_응답은_정확히_한_번이다(Path path) {
        Click click = click(path == Path.ASK ? "ask:11" : "confirm:11");
        switch (path) {
            case NOT_FOUND -> givenCheck(ConfirmCheck.Status.NOT_FOUND, null);
            case NOT_PM -> givenCheck(ConfirmCheck.Status.NOT_PM, null);
            case ALREADY_CONFIRMED -> givenCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT);
            case CHECK_FAILS -> given(confirmService.check(ITEM_ID, PM_ID)).willThrow(new IllegalStateException("DB"));
            case CONFIRMED -> {
                givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
                givenConfirm(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT);
            }
            case RACE_ALREADY -> {
                givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
                givenConfirm(ConfirmCheck.Status.ALREADY_CONFIRMED, CONFIRMED_AT);
            }
            case RACE_NOT_FOUND -> {
                givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
                givenConfirm(ConfirmCheck.Status.NOT_FOUND, null);
            }
            case CONFIRM_FAILS -> {
                givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
                given(confirmService.confirm(ITEM_ID, PM_ID)).willThrow(new IllegalStateException("DB"));
            }
            case EDIT_SEND_FAILS -> {
                givenCheck(ConfirmCheck.Status.CONFIRMABLE, null);
                givenConfirm(ConfirmCheck.Status.CONFIRMED, CONFIRMED_AT);
                willAnswer(inv -> {
                    ((Consumer<Throwable>) inv.getArgument(1)).accept(new RuntimeException("Discord 오류"));
                    return null;
                }).given(click.editAction).queue(any(), any());
            }
            case ASK -> { }
        }

        assertThatCode(() -> listener.onButtonInteraction(click.event)).doesNotThrowAnyException();

        assertExactlyOneResponse(click);
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
        assertThat(responseCalls(click.event)).isEmpty();
        verifyNoInteractions(confirmService);
    }

    @Test
    void 버튼_ID를_action과_항목_ID로_나눈다() {
        assertThat(ButtonListener.ButtonId.parse("confirm:42"))
                .contains(new ButtonListener.ButtonId("confirm", 42L));
        assertThat(ButtonListener.ButtonId.parse("confirm:1:2")).isEmpty();
    }

    // ---- helpers ----

    private void givenCheck(ConfirmCheck.Status status, Instant confirmedAt) {
        given(confirmService.check(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(status, confirmedAt));
    }

    private void givenConfirm(ConfirmCheck.Status status, Instant confirmedAt) {
        given(confirmService.confirm(ITEM_ID, PM_ID)).willReturn(new ConfirmCheck(status, confirmedAt));
    }

    /**
     * 응답 메서드(reply*, defer*, edit*) 호출이 정확히 한 번이고, 그 응답을 실제로 보냈는지(queue) 확인한다.
     * 훅(getHook)은 이미 응답한 뒤에 쓰는 것이라 이 핸들러에서는 쓰지 않는다.
     */
    private static void assertExactlyOneResponse(Click click) {
        List<String> calls = responseCalls(click.event);
        assertThat(calls).as("응답 메서드 호출").hasSize(1);
        verify(click.event, never()).getHook();
        verify(click.event, never()).deferEdit();
        verify(click.event, never()).deferReply();
        if (calls.getFirst().equals("reply")) {
            verify(click.reply).queue();
        } else {
            assertThat(calls.getFirst()).isEqualTo("editButton");
            verify(click.editAction).queue(any(), any());
        }
    }

    private static List<String> responseCalls(ButtonInteractionEvent event) {
        return Mockito.mockingDetails(event).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(name -> name.startsWith("reply") || name.startsWith("defer") || name.startsWith("edit"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Click click(String componentId) {
        Click click = new Click();
        given(click.event.getComponentId()).willReturn(componentId);
        User user = mock(User.class);
        given(user.getIdLong()).willReturn(PM_ID);
        given(click.event.getUser()).willReturn(user);
        given(click.event.reply(anyString())).willReturn(click.reply);
        given(click.reply.setEphemeral(true)).willReturn(click.reply);
        given(click.event.getButton()).willReturn(Button.success("confirm:" + ITEM_ID, "확인했어요"));
        given(click.event.editButton(any())).willReturn(click.editAction);
        return click;
    }

    @SuppressWarnings("unchecked")
    private static final class Click {
        final ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        final ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        final RestAction<Void> editAction = mock(RestAction.class);

        void verifyEphemeralReply(String message) {
            verify(event).reply(message);
            verify(reply).setEphemeral(true);
            verify(reply).queue();
        }
    }
}
