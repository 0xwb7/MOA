package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.discord.ops.DiscordOpsNotifier;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.CycleStatus;
import discord.moa.discordmoa.domain.digest.service.DigestSendResult;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.domain.digest.service.ManualSendResult;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryPreview;
import discord.moa.discordmoa.global.config.DiscordTaskConfig;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

import static discord.moa.discordmoa.discord.listener.CommandSupport.UNEXPECTED_ERROR;
import static discord.moa.discordmoa.discord.listener.CommandSupport.runLong;

/**
 * 운영·확인용 명령어: /수집테스트, /발송테스트, /요약미리보기, /회차상태, /운영알림테스트.
 */
@Slf4j
@Component
class OpsCommandHandler implements SlashCommandHandler {

    private final ChangeCollector changeCollector;
    private final DigestService digestService;
    private final ChangeSummarizer changeSummarizer;
    private final DiscordOpsNotifier opsNotifier;
    private final ExecutorService longTasks;

    OpsCommandHandler(ChangeCollector changeCollector, DigestService digestService, ChangeSummarizer changeSummarizer,
                      DiscordOpsNotifier opsNotifier,
                      @Qualifier(DiscordTaskConfig.DISCORD_TASK_EXECUTOR) ExecutorService longTasks) {
        this.changeCollector = changeCollector;
        this.digestService = digestService;
        this.changeSummarizer = changeSummarizer;
        this.opsNotifier = opsNotifier;
        this.longTasks = longTasks;
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "수집테스트" -> handleCollectTest(event);
            case "발송테스트" -> handleSendTest(event);
            case "요약미리보기" -> handleSummaryPreview(event);
            case "회차상태" -> handleCycleStatus(event);
            case "운영알림테스트" -> handleOpsTest(event);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleCollectTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        runLong(longTasks, event, () -> {
            try {
                TeamCollectResult result = changeCollector.collectTeamByChannel(channelId);
                if (result.repos().isEmpty()) {
                    event.getHook().sendMessage("⚠️ '" + result.teamName() + "' 팀에 등록된 레포가 없어요. /레포등록 으로 먼저 등록해 주세요.").queue();
                    return;
                }

                StringBuilder message = new StringBuilder("✅ '" + result.teamName() + "' 팀 수집 결과\n");
                result.repos().forEach(repo -> message.append("• ").append(describe(repo)).append('\n'));
                event.getHook().sendMessage(message.toString()).queue();
            } catch (IllegalStateException e) {
                event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
            } catch (RuntimeException e) {
                log.error("수집 테스트 중 오류: channelId={}", channelId, e);
                event.getHook().sendMessage(UNEXPECTED_ERROR).queue();
            }
        });
    }

    private void handleSendTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        runLong(longTasks, event, () -> {
            try {
                ManualSendResult result = digestService.sendNowByChannel(channelId);
                TeamCollectResult collected = result.collected();
                DigestSendResult sent = result.sent();

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

    private void handleCycleStatus(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        try {
            CycleStatus status = digestService.cycleStatusByChannel(channelId);
            event.reply(CycleStatusMessage.format(status)).setEphemeral(true).queue();
        } catch (IllegalStateException e) {
            event.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("회차 상태 조회 중 오류: channelId={}", channelId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
        }
    }

    private void handleOpsTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        // 비동기로 보내고 결과가 오면 응답한다 (이벤트 스레드를 막지 않음)
        opsNotifier.sendTest(event.getUser().getName()).whenComplete((result, error) -> {
            String message = error == null ? result.describe() : UNEXPECTED_ERROR;
            event.getHook().sendMessage(message).queue();
        });
    }

    private void handleSummaryPreview(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        runLong(longTasks, event, () -> {
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

    static String describeSend(DigestSendResult result) {
        String summaryNote = result.summaryFallbackReason() == null
                ? ""
                : "\n⚠️ LLM 요약 대신 변경 목록으로 보냈어요: " + result.summaryFallbackReason();
        if (!result.sent()) {
            return "⚠️ 발송 실패: " + result.failureReason() + summaryNote;
        }
        boolean uncollected = !result.uncollectedRepos().isEmpty();
        if (result.type() == DigestType.NO_CHANGE) {
            return uncollected
                    ? "✅ 일부 레포를 확인하지 못해 '변경 없음' 대신 확인하지 못했다는 안내를 보냈어요."
                    : "✅ 변경이 없어 '변경 사항이 없어요' 안내를 보냈어요.";
        }
        String kind = result.type() == DigestType.LLM ? " (LLM 요약)" : "";
        String uncollectedNote = uncollected ? "\n⚠️ 확인하지 못한 레포가 있다는 안내를 메시지 끝에 붙였어요." : "";
        return "✅ 발송 완료" + kind + ": 항목 " + result.itemCount() + "개, 메시지 " + result.messageCount() + "개"
                + summaryNote + uncollectedNote;
    }

    private String describe(RepoCollectResult repo) {
        return switch (repo.outcome()) {
            case COLLECTED -> repo.repoFullName() + ": 커밋 " + repo.commitCount() + "개, 파일 " + repo.fileCount() + "개"
                    + " | force push " + yesNo(repo.forcePushDetected())
                    + " | 잘림 " + yesNo(repo.truncated());
            case NO_CHANGE -> repo.repoFullName() + ": 변경 없음";
            case FAILED -> repo.repoFullName() + ": ❌ 수집 실패: " + repo.failureReason();
            case DEACTIVATED -> repo.repoFullName() + ": ⛔ 등록 해제됨: " + repo.failureReason();
        };
    }

    private String yesNo(boolean value) {
        return value ? "예" : "아니요";
    }
}
