package discord.moa.discordmoa.global.config;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DiscordConfig {

    @Bean(destroyMethod = "shutdown")
    public JDA jda(@Value("${discord.token}") String token,
                   List<ListenerAdapter> listeners) throws InterruptedException {
        return JDABuilder.createLight(token)
                .addEventListeners(listeners.toArray())
                .build()
                .awaitReady();
    }
}
