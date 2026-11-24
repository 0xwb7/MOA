package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.question.service.QuestionService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;

import java.util.Optional;

/**
 * "개발자에게 질문하기" 모달. ID는 "askModal:{itemId}".
 */
final class QuestionModal {

    static final String ACTION = "askModal";
    static final String QUESTION_INPUT_ID = "question";
    static final String TITLE = "개발자에게 질문하기";
    static final String LABEL = "질문 내용";
    static final String PLACEHOLDER = "예: 로그인에 실패하면 어떤 화면이 나와요?";

    private QuestionModal() {
    }

    static String modalId(long itemId) {
        return ACTION + ":" + itemId;
    }

    static Modal create(long itemId) {
        TextInput input = TextInput.create(QUESTION_INPUT_ID, TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setRequiredRange(QuestionService.MIN_LENGTH, QuestionService.MAX_LENGTH)
                .setPlaceholder(PLACEHOLDER)
                .build();
        return Modal.create(modalId(itemId), TITLE)
                .addComponents(Label.of(LABEL, input))
                .build();
    }

    /**
     * "askModal:{양의 정수}"면 항목 ID, 아니면 빈 값.
     */
    static Optional<Long> parseItemId(String modalId) {
        return ButtonListener.ButtonId.parse(modalId)
                .filter(id -> ACTION.equals(id.action()))
                .map(ButtonListener.ButtonId::itemId);
    }
}
