package discord.moa.discordmoa.discord.listener;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 명령어 처리기들이 함께 쓰는 응답 문구와 오래 걸리는 작업 실행.
 */
@Slf4j
final class CommandSupport {

    static final String UNEXPECTED_ERROR = "⚠️ 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.";

    private CommandSupport() {
    }

    /**
     * deferReply 뒤에 호출한다. 종료 중이라 작업을 받지 못하면 바로 안내한다.
     * GitHub·LLM 호출은 오래 걸릴 수 있어서 JDA 이벤트 스레드를 막지 않도록 따로 실행한다.
     */
    static void runLong(ExecutorService longTasks, SlashCommandInteractionEvent event, Runnable task) {
        try {
            longTasks.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("명령 처리를 시작하지 못했어요 (서버 종료 중): command={}", event.getName(), e);
            event.getHook().sendMessage(UNEXPECTED_ERROR).queue();
        }
    }
}
