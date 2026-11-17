package discord.moa.discordmoa.discord.command;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistrar {

    public CommandRegistrar(JDA jda, @Value("${discord.test-guild-id}") String guildId) {
        jda.getGuildById(guildId)
                .updateCommands()
                .addCommands(
                        Commands.slash("ping", "봇 상태 확인"),
                        Commands.slash("연동", "GitHub 계정을 연동해요")
                                .addOption(OptionType.STRING, "github", "GitHub 아이디", true)
                )
                .queue();

    }
}
