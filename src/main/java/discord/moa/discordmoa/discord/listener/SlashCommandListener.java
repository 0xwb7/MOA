package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.discord.ops.DiscordOpsNotifier;
import discord.moa.discordmoa.discord.sender.ChannelPermissions;
import discord.moa.discordmoa.domain.account.service.AccountLinkService;
import discord.moa.discordmoa.domain.collect.service.ChangeCollector;
import discord.moa.discordmoa.domain.collect.service.RepoCollectResult;
import discord.moa.discordmoa.domain.collect.service.TeamCollectResult;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.service.DigestSendResult;
import discord.moa.discordmoa.domain.digest.service.CycleStatus;
import discord.moa.discordmoa.domain.digest.service.DigestService;
import discord.moa.discordmoa.domain.digest.service.ManualSendResult;
import discord.moa.discordmoa.domain.guide.service.UsageGuideService;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryPreview;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.RepoDeactivation;
import discord.moa.discordmoa.domain.team.service.RepoRegistration;
import discord.moa.discordmoa.domain.team.service.TeamRegistration;
import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import discord.moa.discordmoa.global.config.DiscordTaskConfig;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class SlashCommandListener extends ListenerAdapter {

    static final String UNEXPECTED_ERROR = "⚠️ 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.";

    private final AccountLinkService accountLinkService;
    private final TeamService teamService;
    private final TrackedRepoService trackedRepoService;
    private final ChangeCollector changeCollector;
    private final DigestService digestService;
    private final ChangeSummarizer changeSummarizer;
    private final DiscordOpsNotifier opsNotifier;
    private final UsageGuideService usageGuideService;

    // GitHub·LLM 호출은 오래 걸릴 수 있어서 JDA 이벤트 스레드를 막지 않도록 따로 실행한다
    private final ExecutorService longTasks;

    public SlashCommandListener(AccountLinkService accountLinkService, TeamService teamService,
                                TrackedRepoService trackedRepoService, ChangeCollector changeCollector,
                                DigestService digestService, ChangeSummarizer changeSummarizer,
                                DiscordOpsNotifier opsNotifier, UsageGuideService usageGuideService,
                                @Qualifier(DiscordTaskConfig.DISCORD_TASK_EXECUTOR) ExecutorService longTasks) {
        this.accountLinkService = accountLinkService;
        this.teamService = teamService;
        this.trackedRepoService = trackedRepoService;
        this.changeCollector = changeCollector;
        this.digestService = digestService;
        this.changeSummarizer = changeSummarizer;
        this.opsNotifier = opsNotifier;
        this.usageGuideService = usageGuideService;
        this.longTasks = longTasks;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> handlePing(event);
            case "사용법" -> handleUsage(event);
            case "연동" -> handleLink(event);
            case "팀등록" -> handleTeamRegister(event);
            case "레포등록" -> handleRepoRegister(event);
            case "레포해제" -> handleRepoUnregister(event);
            case "레포목록" -> handleRepoList(event);
            case "수집테스트" -> handleCollectTest(event);
            case "발송테스트" -> handleSendTest(event);
            case "요약미리보기" -> handleSummaryPreview(event);
            case "회차상태" -> handleCycleStatus(event);
            case "운영알림테스트" -> handleOpsTest(event);
            default -> event.reply("알 수 없는 명령어예요.").setEphemeral(true).queue();
        }
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
        runLong(event, () -> {
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

    private void handleTeamRegister(SlashCommandInteractionEvent event) {
        String name = event.getOption("name").getAsString();
        long channelId = event.getOption("channel").getAsChannel().getIdLong();
        long pmId = event.getOption("pm").getAsUser().getIdLong();

        try {
            // 옵션의 채널 객체에는 채널별 권한 설정이 빠져 있을 수 있어서, 발송 때처럼 봇의 서버 캐시에서 다시 찾는다
            TextChannel channel = event.getGuild() == null ? null : event.getGuild().getTextChannelById(channelId);
            if (channel == null) {
                event.reply("⚠️ 봇이 이 채널을 찾을 수 없어요. 이 서버의 텍스트 채널인지 확인해 주세요.")
                        .setEphemeral(true).queue();
                return;
            }
            List<Permission> missing = ChannelPermissions.missingForTeamChannel(channel);
            if (!missing.isEmpty()) {
                event.reply(describeMissingTeamChannelPermissions(channel, missing)).setEphemeral(true).queue();
                return;
            }
            TeamRegistration registration = teamService.register(name, channelId, pmId);
            event.reply(describeTeamRegistration(registration)).setEphemeral(true)
                    .setAllowedMentions(List.of()).queue();
        } catch (IllegalStateException e) {
            event.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("팀 등록 중 오류: name={}, channelId={}", name, channelId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
        }
    }

    static String describeMissingTeamChannelPermissions(TextChannel channel, List<Permission> missing) {
        StringBuilder text = new StringBuilder("⚠️ ").append(ChannelPermissions.describeMissing(channel, missing));
        if (missing.contains(Permission.MESSAGE_HISTORY)) {
            text.append("\n" + Permission.MESSAGE_HISTORY.getName() + " 권한은 개발자 질문을 요약 메시지의 답글로 달 때 필요해요.");
        }
        return text.append("\n채널 설정 → 권한에서 MOA 봇(또는 봇 역할)에 이 권한을 준 뒤 다시 /팀등록 해 주세요.").toString();
    }

    static String describeTeamRegistration(TeamRegistration registration) {
        if (registration.created()) {
            return "✅ '" + registration.teamName() + "' 등록이 완료됐어요.";
        }
        String pm = registration.pmUserId().equals(registration.previousPmUserId())
                ? "<@" + registration.pmUserId() + "> 그대로"
                : "<@" + registration.previousPmUserId() + "> → <@" + registration.pmUserId() + ">";
        String text = "✅ '" + registration.teamName() + "' 정보가 수정됐어요 (PM: " + pm + ")";
        if (!registration.channelId().equals(registration.previousChannelId())) {
            text += "\n채널: <#" + registration.previousChannelId() + "> → <#" + registration.channelId() + ">";
        }
        return text;
    }

    private void handleRepoRegister(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String url = event.getOption("url").getAsString();
        long channelId = event.getChannel().getIdLong();
        runLong(event, () -> {
            try {
                RepoRegistration registration = trackedRepoService.register(channelId, url);
                event.getHook().sendMessage(describeRegistration(registration)).queue();
            } catch (IllegalArgumentException | IllegalStateException e) {
                event.getHook().sendMessage("⚠️ " + e.getMessage()).queue();
            } catch (RestClientException e) {
                log.warn("레포 등록 중 GitHub API 호출 실패: url={}", url, e);
                event.getHook().sendMessage("⚠️ GitHub와 통신하는 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.").queue();
            } catch (RuntimeException e) {
                log.error("레포 등록 중 오류: url={}, channelId={}", url, channelId, e);
                event.getHook().sendMessage(UNEXPECTED_ERROR).queue();
            }
        });
    }

    static String describeDeactivation(RepoDeactivation result) {
        StringBuilder text = new StringBuilder("✅ ").append(result.fullName())
                .append(" 레포 등록을 해제했어요. 이제 이 레포의 변경은 수집하지 않아요.\n");
        if (result.discardedCommitCount() > 0) {
            text.append("아직 보내지 않은 변경 ").append(result.discardedCommitCount()).append("건은 보내지 않아요.\n");
        }
        return text.append("다시 수집하려면 /레포등록 을 실행해 주세요.").toString();
    }

    static String describeRegistration(RepoRegistration registration) {
        TrackedRepo repo = registration.repo();
        String branch = " (" + repo.getDefaultBranch() + " 브랜치) ";
        String baseline = repo.getLastProcessedSha() == null
                ? "아직 커밋이 없는 레포라서 첫 커밋부터 수집할게요."
                : "지금 이후의 변경부터 수집할게요.";
        String text = switch (registration.kind()) {
            case REFRESHED -> "✅ " + repo.getFullName() + " 설치 정보를 갱신했어요. 이전 수집 위치부터 이어서 수집해요.";
            case NEW -> "✅ " + repo.getFullName() + " 레포가 등록됐어요." + branch + baseline;
            case REACTIVATED -> "✅ " + repo.getFullName() + " 레포를 다시 등록했어요." + branch
                    + "해제돼 있던 동안의 변경은 건너뛰고 " + baseline;
            case MOVED -> "✅ " + repo.getFullName() + " 레포를 '" + registration.previousTeamName()
                    + "' 팀에서 이 팀으로 옮겨 등록했어요." + branch + baseline;
        };
        return registration.cleanedUpStale() ? text + "\n같은 이름의 이전 레포 등록을 정리했어요." : text;
    }

    private void handleRepoUnregister(SlashCommandInteractionEvent event) {
        String input = event.getOption("repo").getAsString();
        long channelId = event.getChannel().getIdLong();
        try {
            RepoDeactivation result = trackedRepoService.deactivate(channelId, input,
                    event.getUser().getIdLong(), event.getUser().getName(), isAdmin(event.getMember()));
            event.reply(describeDeactivation(result)).setEphemeral(true).queue();
        } catch (IllegalArgumentException | IllegalStateException e) {
            event.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("레포 해제 중 오류: input={}, channelId={}", input, channelId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
        }
    }

    /**
     * 운영진(서버 관리 권한) 여부. 명령어 기본 권한은 서버 설정에서 바꿀 수 있어 실행할 때 다시 확인한다.
     */
    static boolean isAdmin(Member member) {
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private void handleRepoList(SlashCommandInteractionEvent event) {
        long channelId = event.getChannel().getIdLong();
        try {
            event.reply(RepoListMessage.format(trackedRepoService.listActive(channelId))).setEphemeral(true).queue();
        } catch (IllegalStateException e) {
            event.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("레포 목록 조회 중 오류: channelId={}", channelId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
        }
    }

    /**
     * /레포해제 repo 자동완성: 이 채널 팀의 활성 레포.
     */
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!"레포해제".equals(event.getName()) || !"repo".equals(event.getFocusedOption().getName())) {
            return;
        }
        List<Command.Choice> choices;
        try {
            choices = trackedRepoService.autocompleteCandidates(event.getChannelIdLong(), event.getFocusedOption().getValue())
                    .stream().map(name -> new Command.Choice(name, name)).toList();
        } catch (RuntimeException e) {
            log.warn("레포 자동완성 후보 조회 실패: channelId={}", event.getChannelIdLong(), e);
            choices = List.of();
        }
        event.replyChoices(choices).queue();
    }

    private void handleCollectTest(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        long channelId = event.getChannel().getIdLong();
        runLong(event, () -> {
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
        runLong(event, () -> {
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
        runLong(event, () -> {
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

    /**
     * deferReply 뒤에 호출한다. 종료 중이라 작업을 받지 못하면 바로 안내한다.
     */
    private void runLong(SlashCommandInteractionEvent event, Runnable task) {
        try {
            longTasks.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("명령 처리를 시작하지 못했어요 (서버 종료 중): command={}", event.getName(), e);
            event.getHook().sendMessage(UNEXPECTED_ERROR).queue();
        }
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
