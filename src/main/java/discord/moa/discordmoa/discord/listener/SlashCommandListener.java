package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;
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
    private final ChangeCollector changeCollector;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> handlePing(event);
            case "연동" -> handleLink(event);
            case "팀등록" -> handleTeamRegister(event);
            case "레포등록" -> handleRepoRegister(event);
            case "수집테스트" -> handleCollectTest(event);
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

    private void handleCollectTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        try {
            TeamCollectResult result = changeCollector.collectTeamByChannel(event.getChannel().getIdLong());
            if (result.repos().isEmpty()) {
                event.getHook().sendMessage("⚠️ '" + result.teamName() + "' 팀에 등록된 레포가 없어요. /레포등록 으로 먼저 등록해 주세요.").queue();
                return;
            }

            StringBuilder message = new StringBuilder("✅ '" + result.teamName() + "' 팀 수집 결과\n");
            result.repos().forEach(repo -> message.append("• ").append(describe(repo)).append('\n'));
            event.getHook().sendMessage(message.toString()).queue();
        } catch (IllegalStateException e) {
            event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
        }
    }

    private String describe(RepoCollectResult repo) {
        return switch (repo.outcome()) {
            case COLLECTED -> repo.repoFullName() + ": 커밋 " + repo.commitCount() + "개, 파일 " + repo.fileCount() + "개"
                    + " | force push " + yesNo(repo.forcePushDetected())
                    + " | 잘림 " + yesNo(repo.truncated());
            case NO_CHANGE -> repo.repoFullName() + ": 변경 없음";
            case FAILED -> repo.repoFullName() + ": ❌ 수집 실패 (서버 로그를 확인해 주세요)";
        };
    }

    private String yesNo(boolean value) {
        return value ? "예" : "아니요";
    }
}
