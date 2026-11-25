package discord.moa.discordmoa.discord.command;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistrar {

    public CommandRegistrar(JDA jda, @Value("${discord.guild-id}") String guildId) {
        requireGuild(jda, guildId)
                .updateCommands()
                .addCommands(
                        Commands.slash("ping", "봇 상태 확인"),

                        Commands.slash("사용법", "MOA 사용법과 내 설정 상태를 보여줘요"),

                        Commands.slash("연동", "GitHub 계정을 연동해요")
                                .addOption(OptionType.STRING, "github", "GitHub 아이디", true),

                        Commands.slash("팀등록", "팀을 등록하거나 수정해요 (운영진 전용)")
                                .addOption(OptionType.STRING, "name", "팀 이름", true)
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "팀 채팅방 (텍스트 채널)", true)
                                        .setChannelTypes(ChannelType.TEXT))
                                .addOption(OptionType.USER, "pm", "팀 PM", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("레포등록", "팀 GitHub 레포를 등록해요 (팀 채팅방에서 실행)")
                                .addOption(OptionType.STRING, "url", "레포 주소 (예: https://github.com/owner/repo)", true),

                        Commands.slash("레포해제", "이 팀의 GitHub 레포 등록을 해제해요 (운영진 전용, 팀 채팅방에서 실행)")
                                .addOptions(new OptionData(OptionType.STRING, "repo",
                                        "해제할 레포 (목록에서 고르거나 owner/repo·주소 입력)", true, true))
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("레포목록", "이 팀에 등록된 GitHub 레포를 보여줘요 (팀 채팅방에서 실행)"),

                        Commands.slash("수집테스트", "이 채널 팀의 레포 변경을 지금 수집해요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("발송테스트", "이 채널 팀의 변경을 수집해 PM에게 지금 발송해요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("요약미리보기", "이 채널 팀의 미발송 변경을 LLM으로 요약해 나에게만 보여줘요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("회차상태", "이 채널 팀의 다음 정기 회차와 직전 회차 결과를 보여줘요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("운영알림테스트", "운영진 알림 채널로 테스트 알림을 보내요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                )
                .queue();
    }

    /**
     * 설정한 서버를 찾는다. 봇이 그 서버에 없으면 원인이 드러나는 메시지로 앱 시작을 멈춘다.
     * (JDA는 awaitReady 이후라 서버 목록을 이미 받아 둔 상태다)
     */
    static Guild requireGuild(JDA jda, String guildId) {
        String id = guildId == null ? "" : guildId.strip();
        if (!id.matches("\\d{1,20}")) {
            throw new IllegalStateException("DISCORD_GUILD_ID가 올바른 서버 ID가 아니에요: '" + id + "'. 서버 ID(숫자)를 넣어 주세요");
        }
        Guild guild;
        try {
            guild = jda.getGuildById(Long.parseLong(id));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("DISCORD_GUILD_ID가 올바른 서버 ID가 아니에요: '" + id + "'. 서버 ID(숫자)를 넣어 주세요", e);
        }
        if (guild == null) {
            throw new IllegalStateException("봇이 서버(ID: " + id + ")에 초대되지 않았어요. 초대 후 다시 시작해 주세요");
        }
        return guild;
    }
}
