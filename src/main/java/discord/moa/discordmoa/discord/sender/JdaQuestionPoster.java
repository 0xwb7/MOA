package discord.moa.discordmoa.discord.sender;

import discord.moa.discordmoa.domain.question.service.QuestionPost;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

/**
 * 질문을 요약 메시지가 있는 채널에 올린다. 기본은 요약 메시지에 대한 답글이다.
 * 오래 걸릴 수 있는 complete()를 쓰므로 JDA 이벤트 스레드가 아닌 곳에서 호출한다.
 */
@Component
public class JdaQuestionPoster {

    /**
     * @param repliedTo 요약 메시지에 답글로 달았는지. 원래 메시지가 지워져 Discord가 일반 메시지로 보낸 경우도 true다
     * @param replyFallback 메시지 기록 보기 권한이 없어 답글 대신 일반 메시지로 보냈는지
     */
    public record Posted(long messageId, String jumpUrl, boolean repliedTo, boolean replyFallback) {
    }

    public Posted post(GuildMessageChannel channel, QuestionPost post) {
        List<Permission> missing = ChannelPermissions.missing(channel);
        if (!missing.isEmpty()) {
            throw new QuestionPostException(ChannelPermissions.describeMissing(channel, missing));
        }
        boolean reply = post.replyToMessageId() != null && ChannelPermissions.canReply(channel);
        boolean replyFallback = post.replyToMessageId() != null && !reply;

        try {
            MessageCreateAction action = channel.sendMessage(toMessageData(post));
            if (reply) {
                // 원래 메시지가 지워졌으면 실패하지 않고 그냥 채널에 보낸다
                action = action.setMessageReference(post.replyToMessageId()).failOnInvalidReply(false);
            }
            Message sent = action.complete();
            return new Posted(sent.getIdLong(), sent.getJumpUrl(), reply, replyFallback);
        } catch (InsufficientPermissionException e) {
            throw new QuestionPostException(
                    "봇에게 #" + channel.getName() + " 채널의 '" + e.getPermission().getName() + "' 권한이 없어요.", e);
        } catch (ErrorResponseException e) {
            throw new QuestionPostException(
                    "Discord가 #" + channel.getName() + " 채널 게시를 거부했어요: " + e.getErrorCode() + " " + e.getMeaning(), e);
        } catch (RuntimeException e) {
            throw new QuestionPostException("#" + channel.getName() + " 채널 게시 중 오류가 났어요: " + e.getMessage(), e);
        }
    }

    /**
     * 멘션은 지정한 개발자만 동작한다. 허용 타입을 비우고(@everyone·역할·그 밖의 사용자 차단) 개발자 ID만 화이트리스트에 넣는다.
     * 그래서 본문의 &lt;@질문자&gt;는 표시만 되고 알림은 가지 않는다. 답글 대상(봇 자신의 메시지)도 멘션하지 않는다.
     * 커밋 링크가 여러 개라 링크 미리보기(embed)는 끈다.
     */
    static MessageCreateData toMessageData(QuestionPost post) {
        return new MessageCreateBuilder()
                .setContent(post.content())
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .mentionUsers(post.mentionUserIds().stream().map(String::valueOf).toList())
                .mentionRepliedUser(false)
                .setSuppressEmbeds(true)
                .build();
    }
}
