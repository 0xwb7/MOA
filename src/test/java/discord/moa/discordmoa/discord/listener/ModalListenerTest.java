package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.discord.sender.JdaQuestionPoster;
import discord.moa.discordmoa.discord.sender.QuestionPostException;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.question.service.QuestionPost;
import discord.moa.discordmoa.domain.question.service.QuestionPreparation;
import discord.moa.discordmoa.domain.question.service.QuestionService;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ModalListenerTest {

    private static final long ITEM_ID = 11L;
    private static final long ASKER = 7L;
    private static final String JUMP_URL = "https://discord.com/channels/1/100/5555";
    private static final QuestionPost POST = new QuestionPost(ITEM_ID, "1팀", 9000L, "❓ 내용", List.of(1001L), "질문 내용이에요");

    private final QuestionService questionService = mock(QuestionService.class);
    private final JdaQuestionPoster poster = mock(JdaQuestionPoster.class);
    private final OpsNotifier opsNotifier = mock(OpsNotifier.class);
    private final ExecutorService tasks = mock(ExecutorService.class);
    private final ModalListener listener = new ModalListener(questionService, poster, opsNotifier, tasks);

    private final InteractionHook hook = mock(InteractionHook.class);
    private final WebhookMessageCreateAction<?> hookMessage = mock(WebhookMessageCreateAction.class);
    private final TextChannel channel = mock(TextChannel.class);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        given(hook.sendMessage(anyString())).willReturn((WebhookMessageCreateAction) hookMessage);
        given(channel.getId()).willReturn("100");
    }

    // ---- 제출 이벤트 ----

    @Test
    void 제출하면_먼저_본인에게만_보이게_defer한_뒤_나머지는_작업_스레드로_넘긴다() {
        ModalInteractionEvent event = submit("askModal:11", "질문 내용이에요");

        listener.onModalInteraction(event);

        InOrder order = inOrder(event, tasks);
        order.verify(event).deferReply(true);
        order.verify(tasks).execute(any(Runnable.class));
        verifyNoInteractions(questionService, poster);   // DB·Discord 게시는 이벤트 스레드에서 하지 않는다
    }

    @Test
    void 작업_스레드가_받지_못하면_바로_안내한다() {
        ModalInteractionEvent event = submit("askModal:11", "질문 내용이에요");
        willThrow(new RejectedExecutionException()).given(tasks).execute(any(Runnable.class));

        listener.onModalInteraction(event);

        verify(hook).sendMessage(ModalListener.UNEXPECTED_ERROR);
    }

    @Test
    void 다른_모달은_응답하지_않고_무시한다() {
        ModalInteractionEvent event = submit("other:11", "질문");

        listener.onModalInteraction(event);

        verify(event, never()).deferReply(anyBoolean());
        verifyNoInteractions(tasks);
    }

    // ---- 처리 ----

    @Test
    void 게시하고_저장한_뒤_메시지_링크로_안내한다() {
        givenReady();
        given(poster.post(channel, POST)).willReturn(new JdaQuestionPoster.Posted(5555L, JUMP_URL, true, false));

        listener.handle(hook, ITEM_ID, ASKER, "질문 내용이에요", channel);

        InOrder order = inOrder(questionService, poster, hook);
        order.verify(questionService).prepare(ITEM_ID, ASKER, "질문 내용이에요");
        order.verify(poster).post(channel, POST);
        order.verify(questionService).record(ITEM_ID, ASKER, "질문 내용이에요", 5555L);
        order.verify(hook).sendMessage("✅ 질문을 올렸어요: " + JUMP_URL);
        verifyNoInteractions(opsNotifier);
    }

    @Test
    void 항목이_없으면_찾을_수_없다고_안내하고_게시하지_않는다() {
        given(questionService.prepare(ITEM_ID, ASKER, "질문")).willReturn(QuestionPreparation.of(QuestionPreparation.Status.NOT_FOUND));

        listener.handle(hook, ITEM_ID, ASKER, "질문", channel);

        verify(hook).sendMessage("이 항목을 찾을 수 없어요");
        verifyNoInteractions(poster, opsNotifier);
    }

    @Test
    void 연속_질문이면_잠시_후_다시_하라고_안내한다() {
        given(questionService.prepare(ITEM_ID, ASKER, "질문")).willReturn(QuestionPreparation.of(QuestionPreparation.Status.TOO_SOON));

        listener.handle(hook, ITEM_ID, ASKER, "질문", channel);

        verify(hook).sendMessage(ModalListener.TOO_SOON);
        verifyNoInteractions(poster);
    }

    @Test
    void 게시에_실패하면_사용자에게_알리고_운영_경고를_보내고_저장하지_않는다() {
        givenReady();
        given(poster.post(channel, POST)).willThrow(new QuestionPostException("봇에게 #team-1 채널의 Send Messages 권한이 없어요."));

        listener.handle(hook, ITEM_ID, ASKER, "질문 내용이에요", channel);

        verify(hook).sendMessage(ModalListener.POST_FAILED);
        OpsEvent event = opsEvent();
        assertThat(event.type()).isEqualTo(OpsEvent.Type.QUESTION_FAILED);
        assertThat(event.teamName()).isEqualTo("1팀");
        assertThat(event.detail()).contains("질문한 사람: <@7>", "항목 ID: 11", "채널: <#100>", "사유: 게시 실패",
                "Send Messages 권한이 없어요");
        verify(questionService, never()).record(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void 답글_대신_일반_메시지로_보냈으면_운영_경고를_보내고_성공으로_안내한다() {
        givenReady();
        given(poster.post(channel, POST)).willReturn(new JdaQuestionPoster.Posted(5555L, JUMP_URL, false, true));

        listener.handle(hook, ITEM_ID, ASKER, "질문 내용이에요", channel);

        assertThat(opsEvent().type()).isEqualTo(OpsEvent.Type.QUESTION_NOT_REPLIED);
        verify(questionService).record(ITEM_ID, ASKER, "질문 내용이에요", 5555L);
        verify(hook).sendMessage("✅ 질문을 올렸어요: " + JUMP_URL);
    }

    @Test
    void 게시는_됐는데_저장이_실패하면_운영_경고를_보내고_사용자에게는_성공으로_안내한다() {
        givenReady();
        given(poster.post(channel, POST)).willReturn(new JdaQuestionPoster.Posted(5555L, JUMP_URL, true, false));
        willThrow(new IllegalStateException("DB 끊김")).given(questionService).record(ITEM_ID, ASKER, "질문 내용이에요", 5555L);

        listener.handle(hook, ITEM_ID, ASKER, "질문 내용이에요", channel);

        OpsEvent event = opsEvent();
        assertThat(event.type()).isEqualTo(OpsEvent.Type.QUESTION_FAILED);
        assertThat(event.detail()).contains("사유: 게시는 됐지만 저장 실패", JUMP_URL, "DB 끊김");
        verify(hook, times(1)).sendMessage(anyString());
        verify(hook).sendMessage("✅ 질문을 올렸어요: " + JUMP_URL);
    }

    @Test
    void 조회_중_오류가_나면_안내만_한_번_한다() {
        given(questionService.prepare(ITEM_ID, ASKER, "질문")).willThrow(new IllegalStateException("DB"));

        listener.handle(hook, ITEM_ID, ASKER, "질문", channel);

        verify(hook, times(1)).sendMessage(anyString());
        verify(hook).sendMessage(ModalListener.UNEXPECTED_ERROR);
    }

    private void givenReady() {
        given(questionService.prepare(ITEM_ID, ASKER, "질문 내용이에요")).willReturn(QuestionPreparation.ready(POST));
    }

    private OpsEvent opsEvent() {
        ArgumentCaptor<OpsEvent> captor = ArgumentCaptor.forClass(OpsEvent.class);
        verify(opsNotifier).notify(captor.capture());
        return captor.getValue();
    }

    private ModalInteractionEvent submit(String modalId, String question) {
        ModalInteractionEvent event = mock(ModalInteractionEvent.class);
        given(event.getModalId()).willReturn(modalId);
        ModalMapping value = mock(ModalMapping.class);
        given(value.getAsString()).willReturn(question);
        given(event.getValue("question")).willReturn(value);
        User user = mock(User.class);
        given(user.getIdLong()).willReturn(ASKER);
        given(event.getUser()).willReturn(user);
        given(event.getChannel()).willReturn(mock(MessageChannelUnion.class));
        given(event.deferReply(true)).willReturn(mock(ReplyCallbackAction.class, RETURNS_SELF));
        given(event.getHook()).willReturn(hook);
        return event;
    }
}
