package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.discord.sender.ChannelPermissions;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.service.RepoDeactivation;
import discord.moa.discordmoa.domain.team.service.RepoRegistration;
import discord.moa.discordmoa.domain.team.service.TeamRegistration;
import discord.moa.discordmoa.domain.team.service.TeamService;
import discord.moa.discordmoa.domain.team.service.TrackedRepoService;
import discord.moa.discordmoa.global.config.DiscordTaskConfig;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.requests.RestAction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static discord.moa.discordmoa.discord.listener.CommandSupport.UNEXPECTED_ERROR;
import static discord.moa.discordmoa.discord.listener.CommandSupport.runLong;

/**
 * 팀과 레포 설정: /팀등록, /레포등록, /레포해제(+자동완성), /레포목록, /레포전체목록.
 */
@Slf4j
@Component
class TeamCommandHandler implements SlashCommandHandler {

    static final String ADMIN_ONLY = "운영진만 사용할 수 있어요";

    private final TeamService teamService;
    private final TrackedRepoService trackedRepoService;
    private final ExecutorService longTasks;

    TeamCommandHandler(TeamService teamService, TrackedRepoService trackedRepoService,
                       @Qualifier(DiscordTaskConfig.DISCORD_TASK_EXECUTOR) ExecutorService longTasks) {
        this.teamService = teamService;
        this.trackedRepoService = trackedRepoService;
        this.longTasks = longTasks;
    }

    @Override
    public boolean handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "팀등록" -> handleTeamRegister(event);
            case "레포등록" -> handleRepoRegister(event);
            case "레포해제" -> handleRepoUnregister(event);
            case "레포목록" -> handleRepoList(event);
            case "레포전체목록" -> handleAllRepoList(event);
            default -> {
                return false;
            }
        }
        return true;
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
        runLong(longTasks, event, () -> {
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
     * 운영진 전용. 레포 주소가 일반 채널에 보이지 않게 모두 에페메럴로 답한다.
     * DB만 읽으므로 이벤트 스레드에서 바로 답하고, 2000자를 넘으면 이어지는 메시지를 순서대로 보낸다.
     */
    private void handleAllRepoList(SlashCommandInteractionEvent event) {
        if (!isAdmin(event.getMember())) {
            event.reply(ADMIN_ONLY).setEphemeral(true).queue();
            return;
        }
        List<String> messages;
        try {
            messages = AllRepoListMessage.format(trackedRepoService.listAllActiveByTeam());
        } catch (RuntimeException e) {
            log.error("전체 레포 목록 조회 중 오류", e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
            return;
        }
        RestAction<?> chain = event.reply(messages.getFirst()).setEphemeral(true).setAllowedMentions(List.of());
        for (String message : messages.subList(1, messages.size())) {
            chain = chain.flatMap(previous -> event.getHook().sendMessage(message)
                    .setEphemeral(true).setAllowedMentions(List.of()));
        }
        chain.queue();
    }

    /**
     * /레포해제 repo 자동완성: 이 채널 팀의 활성 레포.
     */
    @Override
    public boolean autocomplete(CommandAutoCompleteInteractionEvent event) {
        if (!"레포해제".equals(event.getName()) || !"repo".equals(event.getFocusedOption().getName())) {
            return false;
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
        return true;
    }
}
