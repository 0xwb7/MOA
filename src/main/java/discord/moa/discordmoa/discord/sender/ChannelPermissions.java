package discord.moa.discordmoa.discord.sender;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 팀 채널 발송에 필요한 봇 권한. 발송 직전 확인(JdaDigestSender)과 /팀등록 확인이 같은 기준을 쓴다.
 * 채널 보기 + 메시지 보내기(스레드면 스레드에서 보내기). 메시지 기록 보기는 쓰는 곳이 없어서 요구하지 않는다.
 */
public final class ChannelPermissions {

    private ChannelPermissions() {
    }

    public static List<Permission> required(GuildMessageChannel channel) {
        Permission send = channel instanceof ThreadChannel ? Permission.MESSAGE_SEND_IN_THREADS : Permission.MESSAGE_SEND;
        return List.of(Permission.VIEW_CHANNEL, send);
    }

    /**
     * 채널별 권한 설정(overwrite)까지 반영해서 봇에게 없는 권한을 돌려준다. 모두 있으면 빈 목록.
     */
    public static List<Permission> missing(GuildMessageChannel channel) {
        Member self = channel.getGuild().getSelfMember();
        return required(channel).stream()
                .filter(permission -> !self.hasPermission(channel, permission))
                .toList();
    }

    public static String describeMissing(GuildMessageChannel channel, List<Permission> missing) {
        return "봇에게 #" + channel.getName() + " 채널의 "
                + missing.stream().map(Permission::getName).collect(Collectors.joining(", ")) + " 권한이 없어요.";
    }
}
