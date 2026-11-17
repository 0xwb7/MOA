package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountLinkListener extends ListenerAdapter {

    private final AccountLinkService accountLinkService;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("연동")) {
            return;
        }

        event.deferReply(true).queue();

        String login = event.getOption("github").getAsString();

        try {
            String linked = accountLinkService.link(event.getUser().getIdLong(), login);
            event.getHook().sendMessage("✅ " + linked + " 계정이 연동됐어요.").queue();
        } catch (IllegalArgumentException | IllegalStateException e) {
            event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
        }
    }
}
