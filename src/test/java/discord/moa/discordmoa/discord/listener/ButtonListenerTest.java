package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ButtonListenerTest {

    private final ButtonListener listener = new ButtonListener();

    @ParameterizedTest
    @ValueSource(strings = {"confirm:11", "ask:11"})
    void 확인과_질문_버튼은_준비_중이라고_본인에게만_알린다(String componentId) {
        ButtonInteractionEvent event = event(componentId);
        ReplyCallbackAction reply = replyMock(event);

        listener.onButtonInteraction(event);

        verify(event).reply(ButtonListener.NOT_READY);
        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"confirm", "confirm:", ":11", "confirm:abc", "confirm:1:2", "confirm:-1", "confirm:0",
            "confirm: 11", "confirm:99999999999999999999", "unknown:11"})
    void 형식이_다르거나_모르는_버튼은_예외_없이_무시한다(String componentId) {
        ButtonInteractionEvent event = event(componentId);

        assertThatCode(() -> listener.onButtonInteraction(event)).doesNotThrowAnyException();
        verify(event, never()).reply(anyString());
    }

    @Test
    void 버튼_ID를_action과_항목_ID로_나눈다() {
        assertThat(ButtonListener.ButtonId.parse("confirm:42"))
                .contains(new ButtonListener.ButtonId("confirm", 42L));
        assertThat(ButtonListener.ButtonId.parse("confirm:1:2")).isEmpty();
    }

    private static ButtonInteractionEvent event(String componentId) {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        given(event.getComponentId()).willReturn(componentId);
        return event;
    }

    private static ReplyCallbackAction replyMock(ButtonInteractionEvent event) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        given(event.reply(anyString())).willReturn(reply);
        given(reply.setEphemeral(true)).willReturn(reply);
        return reply;
    }
}
