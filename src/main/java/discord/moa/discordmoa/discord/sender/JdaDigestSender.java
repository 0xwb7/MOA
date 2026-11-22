package discord.moa.discordmoa.discord.sender;

import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Component
public class JdaDigestSender implements DigestSender {

    static final String CONFIRM_LABEL = "확인했어요";
    static final String ASK_LABEL = "개발자에게 질문하기";

    private final JDA jda;
    private final boolean confirmButtonEnabled;
    private final boolean askButtonEnabled;

    public JdaDigestSender(@Lazy JDA jda,
                           @Value("${discord.confirm-button-enabled:false}") boolean confirmButtonEnabled,
                           @Value("${discord.ask-button-enabled:false}") boolean askButtonEnabled) {
        this.jda = jda;
        this.confirmButtonEnabled = confirmButtonEnabled;
        this.askButtonEnabled = askButtonEnabled;
    }

    public static String confirmButtonId(Long itemId) {
        return "confirm:" + itemId;
    }

    public static String askButtonId(Long itemId) {
        return "ask:" + itemId;
    }

    @Override
    public List<Long> send(Long channelId, List<DigestMessage> messages) {
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            throw new DigestSendException(
                    "팀 채널을 찾을 수 없어요 (channelId=" + channelId + "). 채널이 삭제됐거나 봇이 볼 수 없는 채널이에요.",
                    List.of());
        }
        checkPermissions(channel);

        List<Long> sentMessageIds = new ArrayList<>();
        for (DigestMessage message : messages) {
            try {
                Message sent = channel.sendMessage(toMessageData(message, confirmButtonEnabled, askButtonEnabled)).complete();
                sentMessageIds.add(sent.getIdLong());
            } catch (InsufficientPermissionException e) {
                throw new DigestSendException(
                        "봇에게 #" + channel.getName() + " 채널의 '" + e.getPermission().getName() + "' 권한이 없어요.",
                        sentMessageIds, e);
            } catch (ErrorResponseException e) {
                throw new DigestSendException(
                        "Discord가 #" + channel.getName() + " 채널 발송을 거부했어요: "
                                + e.getErrorCode() + " " + e.getMeaning(),
                        sentMessageIds, e);
            } catch (RuntimeException e) {
                throw new DigestSendException(
                        "#" + channel.getName() + " 채널 발송 중 오류가 났어요: " + e.getMessage(),
                        sentMessageIds, e);
            }
        }
        return sentMessageIds;
    }

    private void checkPermissions(GuildMessageChannel channel) {
        Permission send = channel instanceof ThreadChannel ? Permission.MESSAGE_SEND_IN_THREADS : Permission.MESSAGE_SEND;
        Member self = channel.getGuild().getSelfMember();

        List<String> missing = EnumSet.of(Permission.VIEW_CHANNEL, send).stream()
                .filter(permission -> !self.hasPermission(channel, permission))
                .map(Permission::getName)
                .toList();
        if (!missing.isEmpty()) {
            throw new DigestSendException(
                    "봇에게 #" + channel.getName() + " 채널의 " + String.join(", ", missing) + " 권한이 없어요.",
                    List.of());
        }
    }

    /**
     * 항목마다 켜진 버튼만 ActionRow에 넣는다. 둘 다 꺼져 있으면 ActionRow 없이 항목 텍스트만 보낸다.
     */
    static MessageCreateData toMessageData(DigestMessage message, boolean confirmButtonEnabled, boolean askButtonEnabled) {
        List<MessageTopLevelComponent> components = new ArrayList<>();
        for (DigestBlock block : message.blocks()) {
            if (!components.isEmpty()) {
                components.add(Separator.createDivider(Separator.Spacing.SMALL));
            }
            components.add(TextDisplay.of(block.text()));
            if (block.hasButtons()) {
                List<Button> buttons = new ArrayList<>();
                if (confirmButtonEnabled) {
                    buttons.add(Button.success(confirmButtonId(block.itemId()), CONFIRM_LABEL));
                }
                if (askButtonEnabled) {
                    buttons.add(Button.secondary(askButtonId(block.itemId()), ASK_LABEL));
                }
                if (!buttons.isEmpty()) {
                    components.add(ActionRow.of(buttons));
                }
            }
        }

        MessageCreateBuilder builder = new MessageCreateBuilder()
                .useComponentsV2()
                .setComponents(components)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        if (message.mentionUserId() != null) {
            builder.mentionUsers(message.mentionUserId());
        }
        return builder.build();
    }
}
