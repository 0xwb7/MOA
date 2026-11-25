package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 슬래시 명령어를 그룹별 처리기(일반, 팀·레포, 운영)에 넘긴다. 아무도 처리하지 않으면 알 수 없는 명령어로 답한다.
 */
@Component
public class SlashCommandListener extends ListenerAdapter {

    static final String UNKNOWN_COMMAND = "알 수 없는 명령어예요.";

    private final List<SlashCommandHandler> handlers;

    SlashCommandListener(List<SlashCommandHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        for (SlashCommandHandler handler : handlers) {
            if (handler.handle(event)) {
                return;
            }
        }
        event.reply(UNKNOWN_COMMAND).setEphemeral(true).queue();
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        for (SlashCommandHandler handler : handlers) {
            if (handler.autocomplete(event)) {
                return;
            }
        }
    }
}
