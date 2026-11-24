package discord.moa.discordmoa.discord.sender;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 팀 채널에 필요한 봇 권한.
 * <ul>
 *   <li>발송: 채널 보기 + 메시지 보내기(스레드면 스레드에서 보내기). 요약 발송(JdaDigestSender)·운영 알림·질문 게시가 쓴다.</li>
 *   <li>팀 채널 등록(/팀등록): 발송 권한 + 메시지 기록 보기. 개발자 질문을 요약 메시지의 답글로 달 때 필요하다.</li>
 * </ul>
 * 이미 등록된 팀 채널에 기록 보기 권한이 없어도 발송은 막지 않는다. 질문은 그때 일반 메시지로 올린다.
 */
public final class ChannelPermissions {

    private ChannelPermissions() {
    }

    public static List<Permission> required(GuildMessageChannel channel) {
        Permission send = channel instanceof ThreadChannel ? Permission.MESSAGE_SEND_IN_THREADS : Permission.MESSAGE_SEND;
        return List.of(Permission.VIEW_CHANNEL, send);
    }

    public static List<Permission> requiredForTeamChannel(GuildMessageChannel channel) {
        List<Permission> permissions = new ArrayList<>(required(channel));
        permissions.add(Permission.MESSAGE_HISTORY);
        return List.copyOf(permissions);
    }

    /**
     * 채널별 권한 설정(overwrite)까지 반영해서 봇에게 없는 발송 권한을 돌려준다. 모두 있으면 빈 목록.
     */
    public static List<Permission> missing(GuildMessageChannel channel) {
        return missing(channel, required(channel));
    }

    /**
     * /팀등록 기준(발송 + 메시지 기록 보기)으로 봇에게 없는 권한.
     */
    public static List<Permission> missingForTeamChannel(GuildMessageChannel channel) {
        return missing(channel, requiredForTeamChannel(channel));
    }

    /**
     * 답글(message reference)을 달 수 있는지. Discord는 메시지 기록 보기 권한이 없으면 답글을 거부한다.
     */
    public static boolean canReply(GuildMessageChannel channel) {
        return channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY);
    }

    public static String describeMissing(GuildMessageChannel channel, List<Permission> missing) {
        return "봇에게 #" + channel.getName() + " 채널의 "
                + missing.stream().map(Permission::getName).collect(Collectors.joining(", ")) + " 권한이 없어요.";
    }

    private static List<Permission> missing(GuildMessageChannel channel, List<Permission> required) {
        Member self = channel.getGuild().getSelfMember();
        return required.stream()
                .filter(permission -> !self.hasPermission(channel, permission))
                .toList();
    }
}
