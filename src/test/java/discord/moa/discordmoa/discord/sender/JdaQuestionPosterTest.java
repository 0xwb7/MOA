package discord.moa.discordmoa.discord.sender;

import discord.moa.discordmoa.domain.question.service.QuestionPost;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JdaQuestionPosterTest {

    private static final String CONTENT = "❓ <@7>님의 질문\n> 항목: 로그인\n@everyone <@&999> <@8> 급해요\n→ <@1001> <@1002>";

    private final JdaQuestionPoster poster = new JdaQuestionPoster();
    private final TextChannel channel = mock(TextChannel.class);
    private final SelfMember self = mock(SelfMember.class);
    private final MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
    private final Message sent = mock(Message.class);

    @Test
    void 지정한_개발자만_알림을_받고_everyone_역할_질문자_멘션은_동작하지_않는다() {
        MessageCreateData data = JdaQuestionPoster.toMessageData(post(9000L, List.of(1001L, 1002L)));

        assertThat(data.getContent()).isEqualTo(CONTENT);
        assertThat(data.getAllowedMentions()).isEmpty();   // @everyone·@here·역할·그 밖의 사용자 파싱 안 함
        assertThat(data.getMentionedUsers()).containsExactlyInAnyOrder("1001", "1002");   // 질문자 7, 본문의 8은 없음
        assertThat(data.getMentionedRoles()).isEmpty();
        assertThat(data.isMentionRepliedUser()).isFalse();
        assertThat(data.isSuppressEmbeds()).isTrue();
    }

    @Test
    void 멘션할_개발자가_없으면_아무도_알림을_받지_않는다() {
        MessageCreateData data = JdaQuestionPoster.toMessageData(post(9000L, List.of()));

        assertThat(data.getAllowedMentions()).isEmpty();
        assertThat(data.getMentionedUsers()).isEmpty();
    }

    @Test
    void 메시지_기록_보기_권한이_있으면_요약_메시지에_답글로_단다() {
        givenChannel(true, true, true);

        JdaQuestionPoster.Posted posted = poster.post(channel, post(9000L, List.of(1001L)));

        verify(action).setMessageReference(9000L);
        verify(action).failOnInvalidReply(false);   // 원래 메시지가 지워졌으면 그냥 채널에 보낸다
        assertThat(posted).isEqualTo(new JdaQuestionPoster.Posted(5555L, "https://discord.com/channels/1/100/5555", true, false));
    }

    @Test
    void 메시지_기록_보기_권한이_없으면_일반_메시지로_보내고_그렇다고_알려준다() {
        givenChannel(true, true, false);

        JdaQuestionPoster.Posted posted = poster.post(channel, post(9000L, List.of(1001L)));

        verify(action, never()).setMessageReference(anyLong());
        assertThat(posted.repliedTo()).isFalse();
        assertThat(posted.replyFallback()).isTrue();
    }

    @Test
    void 요약_메시지_ID가_없는_항목은_일반_메시지로_보내고_경고_대상이_아니다() {
        givenChannel(true, true, true);

        JdaQuestionPoster.Posted posted = poster.post(channel, post(null, List.of()));

        verify(action, never()).setMessageReference(anyLong());
        verify(action, never()).failOnInvalidReply(anyBoolean());
        assertThat(posted.replyFallback()).isFalse();
    }

    @Test
    void 보내기_권한이_없으면_보내지_않고_빠진_권한을_알려준다() {
        givenChannel(true, false, true);

        assertThatThrownBy(() -> poster.post(channel, post(9000L, List.of())))
                .isInstanceOf(QuestionPostException.class)
                .hasMessageContaining("봇에게 #team-1 채널의 " + Permission.MESSAGE_SEND.getName() + " 권한이 없어요.");
        verify(channel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test
    void Discord가_거부하면_사유를_담아_실패한다() {
        givenChannel(true, true, true);
        ErrorResponseException rejected = mock(ErrorResponseException.class);
        given(rejected.getErrorCode()).willReturn(50013);
        given(rejected.getMeaning()).willReturn("Missing Permissions");
        given(action.complete()).willThrow(rejected);

        assertThatThrownBy(() -> poster.post(channel, post(9000L, List.of())))
                .isInstanceOf(QuestionPostException.class)
                .hasMessageContaining("Discord가 #team-1 채널 게시를 거부했어요: 50013 Missing Permissions");
    }

    private void givenChannel(boolean view, boolean send, boolean history) {
        Guild guild = mock(Guild.class);
        given(channel.getName()).willReturn("team-1");
        given(channel.getGuild()).willReturn(guild);
        given(guild.getSelfMember()).willReturn(self);
        given(self.hasPermission(channel, Permission.VIEW_CHANNEL)).willReturn(view);
        given(self.hasPermission(channel, Permission.MESSAGE_SEND)).willReturn(send);
        given(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).willReturn(history);
        given(channel.sendMessage(any(MessageCreateData.class))).willReturn(action);
        given(action.complete()).willReturn(sent);
        given(sent.getIdLong()).willReturn(5555L);
        given(sent.getJumpUrl()).willReturn("https://discord.com/channels/1/100/5555");
    }

    private static QuestionPost post(Long replyTo, List<Long> mentions) {
        return new QuestionPost(11L, "1팀", replyTo, CONTENT, mentions, "질문");
    }
}
