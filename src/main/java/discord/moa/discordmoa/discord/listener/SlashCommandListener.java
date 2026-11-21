package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.DigestSendResult;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryPreview;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlashCommandListener extends ListenerAdapter {

    private final AccountLinkService accountLinkService;
    private final TeamService teamService;
    private final TrackedRepoService trackedRepoService;
    private final ChangeCollector changeCollector;
    private final DigestService digestService;
    private final ChangeSummarizer changeSummarizer;

    // LLM 요약은 수 분까지 걸릴 수 있어서 JDA 이벤트 스레드를 막지 않도록 따로 실행한다
    private final ExecutorService longTasks = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> handlePing(event);
            case "연동" -> handleLink(event);
            case "팀등록" -> handleTeamRegister(event);
            case "레포등록" -> handleRepoRegister(event);
            case "수집테스트" -> handleCollectTest(event);
            case "발송테스트" -> handleSendTest(event);
            case "요약미리보기" -> handleSummaryPreview(event);
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

    private void handleSendTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        longTasks.execute(() -> {
            try {
                TeamCollectResult collected = changeCollector.collectTeamByChannel(channelId);
                DigestSendResult sent = digestService.sendTeamByChannel(channelId);

                StringBuilder message = new StringBuilder("'" + collected.teamName() + "' 팀 수집 결과\n");
                collected.repos().forEach(repo -> message.append("• ").append(describe(repo)).append('\n'));
                message.append('\n').append(describeSend(sent));
                event.getHook().sendMessage(message.toString()).queue();
            } catch (IllegalStateException e) {
                event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
            } catch (RuntimeException e) {
                log.error("발송 테스트 중 오류: channelId={}", channelId, e);
                event.getHook().sendMessage("⚠️ 발송 중 문제가 생겼어요. 서버 로그를 확인해 주세요.").queue();
            }
        });
    }

    private void handleSummaryPreview(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        longTasks.execute(() -> {
            try {
                SummaryPreview preview = changeSummarizer.previewByChannel(channelId);
                event.getHook().sendMessage(SummaryPreviewMessage.format(preview)).queue();
            } catch (IllegalStateException e) {
                event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
            } catch (RuntimeException e) {
                log.error("요약 미리보기 중 오류: channelId={}", channelId, e);
                event.getHook().sendMessage("⚠️ 요약 중 문제가 생겼어요. 서버 로그를 확인해 주세요.").queue();
            }
        });
    }

    private String describeSend(DigestSendResult result) {
        String summaryNote = result.summaryFallbackReason() == null
                ? ""
                : "\n⚠️ LLM 요약 대신 변경 목록으로 보냈어요: " + result.summaryFallbackReason();
        if (!result.sent()) {
            return "⚠️ 발송 실패: " + result.failureReason() + summaryNote;
        }
        if (result.type() == DigestType.NO_CHANGE) {
            return "✅ 변경이 없어 '변경 사항이 없어요' 안내를 보냈어요.";
        }
        String kind = result.type() == DigestType.LLM ? " (LLM 요약)" : "";
        return "✅ 발송 완료" + kind + ": 항목 " + result.itemCount() + "개, 메시지 " + result.messageCount() + "개"
                + summaryNote;
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
