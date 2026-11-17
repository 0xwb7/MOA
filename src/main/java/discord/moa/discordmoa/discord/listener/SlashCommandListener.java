package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlashCommandListener extends ListenerAdapter {

    private final AccountLinkService accountLinkService;
    private final TeamService teamService;
    private final TrackedRepoService trackedRepoService;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> handlePing(event);
            case "연동" -> handleLink(event);
            case "팀등록" -> handleTeamRegister(event);
            case "레포등록" -> handleRepoRegister(event);
            default -> event.reply("알 수 없는 명령어예요.").setEphemeral(true).queue();
        }
    }

    private void handlePing(SlashCommandInteractionEvent event) {
        event.reply("pong").setEphemeral(true).queue();
    }

    private void handleLink(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String login = event.getOption("github").getAsString();
        try {
            String linked = accountLinkService.link(event.getUser().getIdLong(), login);
            event.getHook().sendMessage("✅ " + linked + " 계정이 연동됐어요.").queue();
        } catch (IllegalArgumentException | IllegalStateException e) {
            event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
        }
    }

    private void handleTeamRegister(SlashCommandInteractionEvent event) {
        String name = event.getOption("name").getAsString();
        long channelId = event.getOption("channel").getAsChannel().getIdLong();
        long pmId = event.getOption("pm").getAsUser().getIdLong();

        try {
            teamService.register(name, channelId, pmId);
            event.reply("✅ '" + name + "' 등록이 완료됐어요.").setEphemeral(true).queue();
        } catch (IllegalStateException e) {
            event.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleRepoRegister(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String url = event.getOption("url").getAsString();
        try {
            TrackedRepo repo = trackedRepoService.register(event.getChannel().getIdLong(), url);
            String baseline = repo.getLastProcessedSha() == null
                    ? "아직 커밋이 없는 레포라서 첫 커밋부터 수집할게요."
                    : "지금 이후의 변경부터 수집할게요.";
            event.getHook().sendMessage("✅ " + repo.getFullName() + " 레포가 등록됐어요. ("
                    + repo.getDefaultBranch() + " 브랜치) " + baseline).queue();
        } catch (IllegalArgumentException | IllegalStateException e) {
            event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
        } catch (RestClientException e) {
            log.warn("레포 등록 중 GitHub API 호출 실패: url={}", url, e);
            event.getHook().sendMessage("⚠️ GitHub와 통신하는 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.").queue();
        }
    }
}
