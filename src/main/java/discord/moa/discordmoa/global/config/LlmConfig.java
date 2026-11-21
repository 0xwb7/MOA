package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OpenAiProperties.class, SummaryProperties.class})
public class LlmConfig {
}
