package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SlashCommandListenerTest {

    private final SlashCommandHandler first = mock(SlashCommandHandler.class);
    private final SlashCommandHandler second = mock(SlashCommandHandler.class);
    private final SlashCommandListener listener = new SlashCommandListener(List.of(first, second));
    private final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);

    @Test
    void 처리기가_명령어를_처리하면_뒤_처리기에_넘기지_않고_따로_답하지_않는다() {
        given(first.handle(event)).willReturn(true);

        listener.onSlashCommandInteraction(event);

        verify(second, never()).handle(any());
        verify(event, never()).reply(anyString());
    }

    @Test
    void 아무_처리기도_처리하지_않으면_알_수_없는_명령어로_답한다() {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        given(event.reply(anyString())).willReturn(reply);

        listener.onSlashCommandInteraction(event);

        verify(first).handle(event);
        verify(second).handle(event);
        verify(event).reply(SlashCommandListener.UNKNOWN_COMMAND);
        verify(reply).setEphemeral(true);
    }

    @Test
    void 자동완성도_처리한_처리기에서_멈춘다() {
        CommandAutoCompleteInteractionEvent autocomplete = mock(CommandAutoCompleteInteractionEvent.class);
        given(first.autocomplete(autocomplete)).willReturn(true);

        listener.onCommandAutoCompleteInteraction(autocomplete);

        verify(second, never()).autocomplete(any());
    }
}
