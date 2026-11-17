package discord.moa.discordmoa.domain.discord.command;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistrar {

    public CommandRegistrar(JDA jda, @Value("${discord.test-guild-id}") String guildId) {
        jda.getGuildById(guildId)
                .updateCommands()
                .addCommands(Commands.slash("ping", "봇 상태 확인"))
                .queue();
    }
}
