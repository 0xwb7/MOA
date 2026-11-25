package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.guide.service.UsageGuideService;
import discord.moa.discordmoa.global.config.DiscordTaskConfig;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static discord.moa.discordmoa.discord.listener.CommandSupport.UNEXPECTED_ERROR;
import static discord.moa.discordmoa.discord.listener.CommandSupport.runLong;

/**
 * 누구나 쓰는 명령어: /ping, /사용법, /연동.
 */
@Slf4j
@Component
class GeneralCommandHandler implements SlashCommandHandler {

    private final AccountLinkService accountLinkService;
    private final UsageGuideService usageGuideService;
    private final ExecutorService longTasks;

    GeneralCommandHandler(AccountLinkService accountLinkService, UsageGuideService usageGuideService,
                          @Qualifier(DiscordTaskConfig.DISCORD_TASK_EXECUTOR) ExecutorService longTasks) {
        this.accountLinkService = accountLinkService;
        this.usageGuideService = usageGuideService;
        this.longTasks = longTasks;
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> handlePing(event);
            case "사용법" -> handleUsage(event);
            case "연동" -> handleLink(event);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handlePing(SlashCommandInteractionEvent event) {
        event.reply("pong").setEphemeral(true).queue();
    }

    /**
     * 누구나, 아무 채널에서. DB만 읽으므로 이벤트 스레드에서 바로 답한다.
     */
    private void handleUsage(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        long userId = event.getUser().getIdLong();
        try {
            event.reply(UsageGuideMessage.format(usageGuideService.build(channelId, userId)))
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
        } catch (RuntimeException e) {
            log.error("사용법 안내 중 오류: channelId={}, userId={}", channelId, userId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
        }
    }

    private void handleLink(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String login = event.getOption("github").getAsString();
        long userId = event.getUser().getIdLong();
        runLong(longTasks, event, () -> {
            try {
                String linked = accountLinkService.link(userId, login);
                event.getHook().sendMessage("✅ " + linked + " 계정이 연동됐어요.").queue();
            } catch (IllegalArgumentException | IllegalStateException e) {
                event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
            } catch (RuntimeException e) {
                log.error("GitHub 계정 연동 중 오류: userId={}, login={}", userId, login, e);
                event.getHook().sendMessage(UNEXPECTED_ERROR).queue();
            }
        });
    }
}
