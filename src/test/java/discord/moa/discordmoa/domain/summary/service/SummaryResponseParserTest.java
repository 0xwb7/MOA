package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.openai.dto.OpenAiResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.sha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryResponseParserTest {

    private static final Set<String> KNOWN = Set.of(sha('a'), sha('b'), sha('c'), sha('d'));

    private final SummaryResponseParser parser = new SummaryResponseParser(JsonMapper.builder().build());

    @Test
    void 정상_응답을_항목으로_바꾼다() {
        List<SummaryItem> items = parser.parseItems("""
                {"items":[
                  {"status":"READY","title":"로그인 기능을 써 볼 수 있어요","commitShas":["%s","%s"]},
                  {"status":"PARTIAL","title":"회원가입은 서버만 준비됐어요","commitShas":["%s"]}
                ]}""".formatted(sha('a'), sha('b'), sha('c')), KNOWN);

        assertThat(items).containsExactly(
                new SummaryItem(SummaryStatus.READY, "로그인 기능을 써 볼 수 있어요", List.of(sha('a'), sha('b'))),
                new SummaryItem(SummaryStatus.PARTIAL, "회원가입은 서버만 준비됐어요", List.of(sha('c'))));
    }

    @Test
    void INTERNAL은_맨_뒤로_보내고_여러_개면_하나로_합친다() {
        List<SummaryItem> items = parser.parseItems("""
                {"items":[
                  {"status":"INTERNAL","title":"코드 정리","commitShas":["%s"]},
                  {"status":"PARTIAL","title":"회원가입 서버 준비","commitShas":["%s"]},
                  {"status":"INTERNAL","title":"설정 변경","commitShas":["%s","%s"]},
                  {"status":"READY","title":"로그인","commitShas":["%s"]}
                ]}""".formatted(sha('a'), sha('b'), sha('c'), sha('a'), sha('d')), KNOWN);

        assertThat(items).extracting(SummaryItem::status)
                .containsExactly(SummaryStatus.PARTIAL, SummaryStatus.READY, SummaryStatus.INTERNAL);
        assertThat(items.getLast().title()).isEqualTo("코드 정리, 설정 변경");
        assertThat(items.getLast().commitShas()).containsExactly(sha('a'), sha('c'));
    }

    @Test
    void 알_수_없는_status는_스키마_위반이다() {
        assertInvalid("""
                {"items":[{"status":"DONE","title":"로그인","commitShas":["%s"]}]}""".formatted(sha('a')), "스키마");
    }

    @Test
    void 제목이_비었거나_commitShas가_없으면_스키마_위반이다() {
        assertInvalid("""
                {"items":[{"status":"READY","title":"  ","commitShas":["%s"]}]}""".formatted(sha('a')), "title");
        assertInvalid("""
                {"items":[{"status":"READY","title":"로그인","commitShas":[]}]}""", "commitShas");
        assertInvalid("""
                {"items":[{"status":"READY","title":"로그인"}]}""", "commitShas");
    }

    @Test
    void JSON이_아니면_스키마_위반이다() {
        assertInvalid("요약: 로그인 기능이 추가됐어요", "스키마");
        assertInvalid("{\"summary\":[]}", "비어 있어요");
    }

    @Test
    void 입력에_없는_SHA가_있으면_실패한다() {
        assertInvalid("""
                {"items":[{"status":"READY","title":"로그인","commitShas":["%s","%s"]}]}"""
                .formatted(sha('a'), sha('f')), "입력에 없는 커밋 SHA");
        assertInvalid("""
                {"items":[{"status":"READY","title":"로그인","commitShas":["aaaaaaa"]}]}""", "입력에 없는 커밋 SHA");
    }

    @Test
    void items가_비어_있으면_실패한다() {
        assertInvalid("{\"items\":[]}", "비어 있어요");
    }

    @Test
    void 너무_긴_제목은_자른다() {
        List<SummaryItem> items = parser.parseItems("""
                {"items":[{"status":"READY","title":"%s","commitShas":["%s"]}]}"""
                .formatted("가".repeat(500), sha('a')), KNOWN);

        assertThat(items.getFirst().title()).hasSize(SummaryResponseParser.MAX_TITLE_LENGTH + 1).endsWith("…");
    }

    @Test
    void 응답_본문에서_output_text를_꺼낸다() {
        OpenAiResponse response = parser.readEnvelope("""
                {"id":"resp_1","status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","summary":[]},
                  {"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\\"items\\":[]}","annotations":[]}]}
                ],"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":0},"output_tokens":80,"total_tokens":1280}}""");

        assertThat(parser.outputText(response)).isEqualTo("{\"items\":[]}");
        assertThat(response.usage().inputTokens()).isEqualTo(1200);
        assertThat(response.usage().outputTokens()).isEqualTo(80);
    }

    @Test
    void 미완료_응답이나_거절은_실패한다() {
        OpenAiResponse incomplete = parser.readEnvelope("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""");
        assertThatThrownBy(() -> parser.outputText(incomplete))
                .isInstanceOf(SummaryValidationException.class)
                .hasMessageContaining("max_output_tokens");

        OpenAiResponse refusal = parser.readEnvelope("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"할 수 없어요"}]}]}""");
        assertThatThrownBy(() -> parser.outputText(refusal))
                .isInstanceOf(SummaryValidationException.class)
                .hasMessageContaining("거절");

        assertThatThrownBy(() -> parser.readEnvelope("<html>bad gateway</html>"))
                .isInstanceOf(SummaryValidationException.class);
    }

    @Test
    void 오류_응답에서_메시지를_꺼낸다() {
        assertThat(parser.errorMessage("""
                {"error":{"message":"The model `gpt-x` does not exist","type":"invalid_request_error","code":"model_not_found"}}"""))
                .isEqualTo("The model `gpt-x` does not exist");
        assertThat(parser.errorMessage("not json")).isNull();
        assertThat(parser.errorMessage(null)).isNull();
    }

    private void assertInvalid(String outputText, String messagePart) {
        assertThatThrownBy(() -> parser.parseItems(outputText, KNOWN))
                .isInstanceOf(SummaryValidationException.class)
                .hasMessageContaining(messagePart);
    }
}
