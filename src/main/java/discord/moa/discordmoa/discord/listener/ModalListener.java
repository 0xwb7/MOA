package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.discord.sender.JdaQuestionPoster;
import discord.moa.discordmoa.discord.sender.QuestionPostException;
import discord.moa.discordmoa.domain.ops.OpsEvent;
import discord.moa.discordmoa.domain.ops.OpsNotifier;
import discord.moa.discordmoa.domain.question.service.QuestionPost;
import discord.moa.discordmoa.domain.question.service.QuestionPreparation;
import discord.moa.discordmoa.domain.question.service.QuestionService;
import discord.moa.discordmoa.global.config.DiscordTaskConfig;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * "개발자에게 질문하기" 모달 제출. 먼저 deferReply(본인에게만)로 응답하고, 나머지는 discordTaskExecutor에서 처리한다.
 * <p>
 * 순서: 조회·검사(읽기 트랜잭션) → 게시(트랜잭션 밖) → 저장(쓰기 트랜잭션) → 결과 안내.
 * 게시는 모달을 제출한 채널, 즉 원래 요약 메시지가 있는 채널에 한다.
 */
@Slf4j
@Component
public class ModalListener extends ListenerAdapter {

    static final String UNEXPECTED_ERROR = "⚠️ 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.";
    static final String NOT_FOUND = "이 항목을 찾을 수 없어요";
    static final String TOO_SOON = "같은 항목에는 1분에 한 번만 질문할 수 있어요. 잠시 후 다시 질문해 주세요.";
    static final String INVALID_CONTENT = "질문은 " + QuestionService.MIN_LENGTH + "자 이상 "
            + QuestionService.MAX_LENGTH + "자 이하로 써 주세요.";
    static final String POST_FAILED = "⚠️ 질문을 올리지 못했어요. 운영진에게 알렸어요.";
    static final String POSTED = "✅ 질문을 올렸어요: ";

    private final QuestionService questionService;
    private final JdaQuestionPoster poster;
    private final OpsNotifier opsNotifier;
    private final ExecutorService tasks;

    public ModalListener(QuestionService questionService, JdaQuestionPoster poster, OpsNotifier opsNotifier,
                         @Qualifier(DiscordTaskConfig.DISCORD_TASK_EXECUTOR) ExecutorService tasks) {
        this.questionService = questionService;
        this.poster = poster;
        this.opsNotifier = opsNotifier;
        this.tasks = tasks;
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Optional<Long> itemId = QuestionModal.parseItemId(event.getModalId());
        if (itemId.isEmpty()) {
            log.warn("모르는 모달이라 무시해요: modalId={}", event.getModalId());
            return;
        }
        ModalMapping value = event.getValue(QuestionModal.QUESTION_INPUT_ID);
        String question = value == null ? null : value.getAsString();
        long askerId = event.getUser().getIdLong();
        GuildMessageChannel channel = event.getChannel() instanceof GuildMessageChannel guildChannel ? guildChannel : null;

        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        try {
            tasks.execute(() -> handle(hook, itemId.get(), askerId, question, channel));
        } catch (RejectedExecutionException e) {
            log.warn("종료 중이라 질문을 처리하지 못했어요: itemId={}", itemId.get());
            hook.sendMessage(UNEXPECTED_ERROR).queue();
        }
    }

    /**
     * 사용자에게는 어떤 경로로든 훅 메시지를 정확히 한 번 보낸다.
     */
    void handle(InteractionHook hook, long itemId, long askerId, String question, GuildMessageChannel channel) {
        try {
            QuestionPreparation preparation = questionService.prepare(itemId, askerId, question);
            switch (preparation.status()) {
                case INVALID_CONTENT -> hook.sendMessage(INVALID_CONTENT).queue();
                case NOT_FOUND -> hook.sendMessage(NOT_FOUND).queue();
                case TOO_SOON -> hook.sendMessage(TOO_SOON).queue();
                case READY -> postAndRecord(hook, askerId, preparation.post(), channel);
            }
        } catch (RuntimeException e) {
            log.error("질문 처리 중 오류: itemId={}, askerId={}", itemId, askerId, e);
            hook.sendMessage(UNEXPECTED_ERROR).queue();
        }
    }

    private void postAndRecord(InteractionHook hook, long askerId, QuestionPost post, GuildMessageChannel channel) {
        JdaQuestionPoster.Posted posted;
        try {
            if (channel == null) {
                throw new QuestionPostException("모달을 제출한 곳이 서버의 메시지 채널이 아니에요.");
            }
            posted = poster.post(channel, post);
        } catch (QuestionPostException e) {
            log.warn("질문 게시 실패: itemId={}, askerId={}, reason={}", post.itemId(), askerId, e.getMessage());
            notifyOps(OpsEvent.Type.QUESTION_FAILED, post, askerId, channel, "사유: 게시 실패\n" + e.getMessage());
            hook.sendMessage(POST_FAILED).queue();
            return;
        }

        if (posted.replyFallback()) {
            notifyOps(OpsEvent.Type.QUESTION_NOT_REPLIED, post, askerId, channel,
                    "봇에게 " + Permission.MESSAGE_HISTORY.getName() + " 권한이 없어 요약 메시지의 답글로 달지 못했어요. "
                            + "채널 권한을 확인해 주세요.");
        }

        try {
            questionService.record(post.itemId(), askerId, post.question(), posted.messageId());
        } catch (RuntimeException e) {
            // 질문은 이미 올라갔으니 사용자에게는 성공으로 안내한다. 기록이 빠지면 연속 질문 제한만 적용되지 않는다
            log.error("질문은 게시했지만 저장하지 못했어요: itemId={}, askerId={}, messageId={}",
                    post.itemId(), askerId, posted.messageId(), e);
            notifyOps(OpsEvent.Type.QUESTION_FAILED, post, askerId, channel,
                    "사유: 게시는 됐지만 저장 실패\n게시한 메시지: " + posted.jumpUrl() + "\n오류: " + e);
        }
        hook.sendMessage(POSTED + posted.jumpUrl()).queue();
    }

    private void notifyOps(OpsEvent.Type type, QuestionPost post, long askerId, GuildMessageChannel channel,
                           String reason) {
        String where = channel == null ? "알 수 없음" : "<#" + channel.getId() + ">";
        opsNotifier.notify(new OpsEvent(type, post.teamName(), null,
                "질문한 사람: <@" + askerId + ">\n항목 ID: " + post.itemId() + "\n채널: " + where + "\n" + reason));
    }
}
