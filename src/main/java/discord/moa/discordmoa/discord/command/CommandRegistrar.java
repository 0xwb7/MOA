package discord.moa.discordmoa.discord.command;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistrar {

    public CommandRegistrar(JDA jda, @Value("${discord.test-guild-id}") String guildId) {
        jda.getGuildById(guildId)
                .updateCommands()
                .addCommands(
                        Commands.slash("ping", "봇 상태 확인"),

                        Commands.slash("연동", "GitHub 계정을 연동해요")
                                .addOption(OptionType.STRING, "github", "GitHub 아이디", true),

                        Commands.slash("팀등록", "팀을 등록하거나 수정해요 (운영진 전용)")
                                .addOption(OptionType.STRING, "name", "팀 이름", true)
                                .addOption(OptionType.CHANNEL, "channel", "팀 채팅방", true)
                                .addOption(OptionType.USER, "pm", "팀 PM", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("레포등록", "팀 GitHub 레포를 등록해요 (팀 채팅방에서 실행)")
                                .addOption(OptionType.STRING, "url", "레포 주소 (예: https://github.com/owner/repo)", true),

                        Commands.slash("수집테스트", "이 채널 팀의 레포 변경을 지금 수집해요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                        Commands.slash("발송테스트", "이 채널 팀의 변경을 수집해 PM에게 지금 발송해요 (운영진 전용)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                )
                .queue();
    }
}
