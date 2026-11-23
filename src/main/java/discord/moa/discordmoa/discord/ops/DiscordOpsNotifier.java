package discord.moa.discordmoa.discord.ops;

import discord.moa.discordmoa.discord.sender.ChannelPermissions;
import discord.moa.discordmoa.domain.ops.LoggingOpsNotifier;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 운영진 전용 채널(discord.ops-channel-id)로 운영 알림을 보낸다.
 * <ul>
 *     <li>로그는 항상 먼저 남긴다(LoggingOpsNotifier). Discord 전송이 실패해도 기록은 남는다.</li>
 *     <li>채널 설정이 없거나, 채널을 찾을 수 없거나, 권한이 없으면 로그만 남긴다.</li>
 *     <li>예외를 던지지 않고, queue()로 비동기 전송해 호출한 스레드(준비·발송)를 막지 않는다.</li>
 * </ul>
 */
@Slf4j
@Primary
@Component
public class DiscordOpsNotifier implements OpsNotifier {

    static final String STARTED_MESSAGE = "MOA가 시작됐어요";

    private final JDA jda;
    private final LoggingOpsNotifier logging;
    private final Long channelId;
    private final Long roleId;

    public DiscordOpsNotifier(@Lazy JDA jda, LoggingOpsNotifier logging,
                              @Value("${discord.ops-channel-id:}") String channelId,
                              @Value("${discord.ops-role-id:}") String roleId) {
        this.jda = jda;
        this.logging = logging;
        this.channelId = parseId("discord.ops-channel-id", channelId);
        this.roleId = parseId("discord.ops-role-id", roleId);
    }

    @Override
    public void notify(OpsEvent event) {
        try {
            logging.notify(event);
        } catch (RuntimeException e) {
            log.warn("운영 알림 로그 기록 실패: {}", event, e);
        }
        try {
            Target target = resolve();
            if (target.channel() == null) {
                return;   // 설정 없음·채널 없음·권한 없음: 위에서 로그로 남겼다
            }
            target.channel().sendMessage(OpsMessageFormatter.format(event, roleId)).queue(
                    sent -> { },
                    error -> log.warn("운영 알림을 Discord로 보내지 못했어요 (로그에는 남았어요): type={}, error={}",
                            event.type(), error.toString()));
        } catch (RuntimeException e) {
            log.warn("운영 알림을 Discord로 보내지 못했어요 (로그에는 남았어요): type={}", event.type(), e);
        }
    }

    /**
     * /운영알림테스트: 테스트 알림을 보내고 결과를 돌려준다. 예외를 던지지 않는다.
     */
    public CompletableFuture<OpsTestResult> sendTest(String requestedBy) {
        OpsEvent event = OpsEvent.of(OpsEvent.Type.OPS_TEST, "운영 알림 테스트예요 (요청: " + requestedBy + ")");
        try {
            logging.notify(event);
            Target target = resolve();
            if (target.channel() == null) {
                return CompletableFuture.completedFuture(target.problem());
            }
            String channelName = "#" + target.channel().getName();
            return target.channel().sendMessage(OpsMessageFormatter.format(event, roleId)).submit()
                    .handle((sent, error) -> error == null
                            ? new OpsTestResult(OpsTestResult.Status.SENT, channelName)
                            : new OpsTestResult(OpsTestResult.Status.FAILED, error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("운영 알림 테스트 전송 실패", e);
            return CompletableFuture.completedFuture(new OpsTestResult(OpsTestResult.Status.FAILED, e.getMessage()));
        }
    }

    /**
     * 시작 시 설정을 확인해 문제가 있으면 WARN을 남기고, 운영 채널에 시작 알림을 보낸다 (예상하지 못한 재시작을 알아채기 위함).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStarted() {
        try {
            Target target = resolve();
            if (target.channel() == null) {
                log.warn("운영 알림을 Discord로 보낼 수 없어 로그로만 남겨요: {}", target.problem().describe());
            } else {
                warnIfRoleCannotBeMentioned(target.channel());
            }
        } catch (RuntimeException e) {
            log.warn("운영 알림 채널 확인 중 오류 (로그로만 남겨요)", e);
        }
        notify(OpsEvent.of(OpsEvent.Type.STARTED, STARTED_MESSAGE));
    }

    private Target resolve() {
        if (channelId == null) {
            return Target.unavailable(new OpsTestResult(OpsTestResult.Status.NOT_CONFIGURED, null));
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            return Target.unavailable(new OpsTestResult(OpsTestResult.Status.CHANNEL_NOT_FOUND, "channelId=" + channelId));
        }
        // 팀 채널 발송과 같은 기준 (채널 보기 + 메시지 보내기)
        List<String> missing = ChannelPermissions.missing(channel).stream().map(Permission::getName).toList();
        if (!missing.isEmpty()) {
            return Target.unavailable(new OpsTestResult(OpsTestResult.Status.NO_PERMISSION,
                    "#" + channel.getName() + " 채널의 " + String.join(", ", missing)));
        }
        return new Target(channel, null);
    }

    private void warnIfRoleCannotBeMentioned(GuildMessageChannel channel) {
        if (roleId == null) {
            return;
        }
        Role role = channel.getGuild().getRoleById(roleId);
        if (role == null) {
            log.warn("운영진 역할(discord.ops-role-id={})을 찾을 수 없어요. 🔴 알림이 울리지 않아요.", roleId);
        } else if (!role.isMentionable()
                && !channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE)) {
            log.warn("운영진 역할 '{}'은 멘션할 수 없는 역할이고 봇에게 '{}' 권한도 없어서 🔴 알림이 울리지 않아요.",
                    role.getName(), Permission.MESSAGE_MENTION_EVERYONE.getName());
        }
    }

    private static Long parseId(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            log.warn("{} 값이 숫자가 아니라 무시해요: {}", key, value);
            return null;
        }
    }

    private record Target(GuildMessageChannel channel, OpsTestResult problem) {
        static Target unavailable(OpsTestResult problem) {
            return new Target(null, problem);
        }
    }
}
