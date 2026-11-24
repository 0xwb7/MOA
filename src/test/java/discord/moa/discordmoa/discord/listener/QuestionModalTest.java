package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionModalTest {

    @Test
    void 모달은_문단형_질문_입력_하나다() {
        Modal modal = QuestionModal.create(42L);

        assertThat(modal.getId()).isEqualTo("askModal:42");
        assertThat(modal.getTitle()).isEqualTo("개발자에게 질문하기");
        assertThat(modal.getComponents()).hasSize(1);
        Label label = modal.getComponents().getFirst().asLabel();
        assertThat(label.getLabel()).isEqualTo("질문 내용");
        TextInput input = label.getChild().asTextInput();
        assertThat(input.getCustomId()).isEqualTo("question");
        assertThat(input.getStyle()).isEqualTo(TextInputStyle.PARAGRAPH);
        assertThat(input.isRequired()).isTrue();
        assertThat(input.getMinLength()).isEqualTo(5);
        assertThat(input.getMaxLength()).isEqualTo(1000);
        assertThat(input.getPlaceHolder()).isEqualTo("예: 로그인에 실패하면 어떤 화면이 나와요?");
    }

    @Test
    void 모달_ID에서_항목_ID를_꺼낸다() {
        assertThat(QuestionModal.parseItemId("askModal:42")).contains(42L);
        assertThat(QuestionModal.parseItemId(QuestionModal.modalId(7L))).contains(7L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"askModal", "askModal:", "askModal:abc", "askModal:0", "askModal:-1", "askModal:1:2",
            "askModal:99999999999999999999", "ask:42", "confirm:42", "other:42"})
    void 형식이_다르거나_다른_모달이면_빈_값이다(String modalId) {
        assertThat(QuestionModal.parseItemId(modalId)).isEmpty();
    }
}
