package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * 명령어 그룹 하나를 처리한다. 자기 명령어가 아니면 false를 돌려준다.
 */
interface SlashCommandHandler {

    boolean handle(SlashCommandInteractionEvent event);

    default boolean autocomplete(CommandAutoCompleteInteractionEvent event) {
        return false;
    }
}
