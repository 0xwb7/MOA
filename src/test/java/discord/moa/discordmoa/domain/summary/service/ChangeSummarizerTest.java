package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.summary.entity.LlmCall;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.repository.LlmCallRepository;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.global.config.OpenAiProperties;
import discord.moa.discordmoa.global.config.SummaryProperties;
import discord.moa.discordmoa.openai.client.OpenAiCallException;
import discord.moa.discordmoa.openai.client.OpenAiClient;
import discord.moa.discordmoa.openai.dto.OpenAiRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.batch;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.commit;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.file;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.properties;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.repo;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.sha;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeSummarizerTest {

    private static final Long TEAM_ID = 10L;
    // 2026-11-20 12:00 KST
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private ChangeBatchRepository changeBatchRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private LlmCallRepository llmCallRepository;
    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private PreviousItemsReader previousItemsReader;

    private final List<ChangeBatch> batches = new ArrayList<>();
    private final List<LlmCall> calls = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();

    private final TrackedRepo backend = repo(1L, "team1/backend");
    private final TrackedRepo frontend = repo(2L, "team1/frontend");

    @BeforeEach
    void setUp() {
        given(changeBatchRepository.findAllOfActiveReposByTeamIdAndStatus(TEAM_ID, ChangeBatchStatus.COLLECTED))
                .willAnswer(inv -> List.copyOf(batches));
        given(llmCallRepository.save(any(LlmCall.class))).willAnswer(inv -> {
            calls.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        given(openAiClient.isConfigured()).willReturn(true);

        batches.add(batch(1L, backend, List.of(commit(sha('a'), "feat: 로그인 API 추가")),
                List.of(file("src/main/java/auth/LoginController.java", "+@PostMapping(\"/login\")"))));
        batches.add(batch(2L, frontend, List.of(commit(sha('b'), "feat: 로그인 화면"), commit(sha('c'), "chore: eslint 설정")),
                List.of(file("src/pages/Login.tsx", "+<LoginForm />"), file(".eslintrc.json", "+{}"))));
    }

    @Test
    void 요약에_성공하면_항목과_통계를_돌려주고_호출을_기록한다() {
        given(openAiClient.createResponse(any())).willReturn(response("""
                {"items":[
                  {"status":"INTERNAL","title":"코드 검사 설정","commitShas":["%s"]},
                  {"status":"READY","title":"로그인 기능이 완성됐어요","commitShas":["%s","%s"]}
                ]}""".formatted(sha('c'), sha('a'), sha('b'))));

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.changeBatchIds()).containsExactly(1L, 2L);
        assertThat(result.items()).extracting(SummaryItem::status)
                .containsExactly(SummaryStatus.READY, SummaryStatus.INTERNAL);
        assertThat(result.stats()).satisfies(stats -> {
            assertThat(stats.promptVersion()).isEqualTo("v1");
            assertThat(stats.model()).isEqualTo("gpt-test");
            assertThat(stats.inputChars()).isPositive();
            assertThat(stats.inputTokens()).isEqualTo(1200);
            assertThat(stats.outputTokens()).isEqualTo(150);
            assertThat(stats.attempts()).isEqualTo(1);
        });

        LlmCall call = calls.getFirst();
        assertThat(calls).hasSize(1);
        assertThat(call.isSuccess()).isTrue();
        assertThat(call.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(call.getPurpose()).isEqualTo(LlmCallPurpose.DIGEST);
        assertThat(call.getPromptVersion()).isEqualTo("v1");
        assertThat(call.getModel()).isEqualTo("gpt-test");
        assertThat(call.getInputChars()).isEqualTo(result.stats().inputChars());
        assertThat(call.getInputTokens()).isEqualTo(1200);
        assertThat(call.getOutputTokens()).isEqualTo(150);
        assertThat(call.getResponseBody()).contains("\"usage\"");
        assertThat(call.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 요청은_프롬프트_파일과_strict_스키마를_쓴다() {
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));

        summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        ArgumentCaptor<OpenAiRequest> captor = ArgumentCaptor.forClass(OpenAiRequest.class);
        verify(openAiClient).createResponse(captor.capture());
        OpenAiRequest request = captor.getValue();
        assertThat(request.model()).isEqualTo("gpt-test");
        assertThat(request.instructions()).isEqualTo(new SummaryPrompt(properties(60_000, 40, 1)).text())
                .contains("READY", "PARTIAL", "INTERNAL");
        assertThat(request.input()).contains("# 레포: team1/backend", "# 레포: team1/frontend", sha('a'), "<LoginForm />");
        assertThat(request.reasoning().effort()).isEqualTo("low");
        assertThat(request.maxOutputTokens()).isEqualTo(8000);
        assertThat(request.store()).isFalse();
        assertThat(request.text().format().type()).isEqualTo("json_schema");
        assertThat(request.text().format().strict()).isTrue();

        String json = JSON.writeValueAsString(request);
        assertThat(json).contains("\"max_output_tokens\":8000", "\"additionalProperties\":false",
                "\"required\":[\"status\",\"title\",\"commitShas\"]", "\"enum\":[\"READY\",\"PARTIAL\",\"INTERNAL\"]");
    }

    // ---- 프롬프트 v2: 이전에 보고한 항목 ----

    @Test
    void v2는_이전에_보고한_항목을_입력에_넣고_응답의_이전_항목_ID를_받는다() {
        given(previousItemsReader.read(TEAM_ID)).willReturn(List.of(new PreviousItem(12L,
                Instant.parse("2026-11-19T12:00:00Z"), SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)")));
        given(openAiClient.createResponse(any())).willReturn(response("""
                {"items":[{"status":"READY","title":"로그인 기능이 완성됐어요","commitShas":["%s","%s"],"relatedPreviousItemId":12}]}"""
                .formatted(sha('a'), sha('b'))));

        SummaryResult result = summarizer(properties("v2", 60_000, 40, 1_000_000)).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(SummaryStatus.READY);
            assertThat(item.relatedPreviousItemId()).isEqualTo(12L);
        });
        OpenAiRequest request = capturedRequest();
        assertThat(request.input()).startsWith("## 이전에 PM에게 이미 보고한 기능 (참고용. 이번 변경 아님)\n"
                + "- [P12] 11-19 21:00 🟡 로그인 서버 기능이 준비됐어요 (화면 연결 전)\n");
        assertThat(request.instructions()).contains("[이전에 보고한 기능]", "relatedPreviousItemId");
        assertThat(JSON.writeValueAsString(request)).contains(
                "\"relatedPreviousItemId\":{\"type\":[\"integer\",\"null\"]}",
                "\"required\":[\"status\",\"title\",\"commitShas\",\"relatedPreviousItemId\"]");
        assertThat(calls.getFirst().getPromptVersion()).isEqualTo("v2");
        assertThat(result.stats().promptVersion()).isEqualTo("v2");
    }

    // ---- 프롬프트 v4: API 주소 ----

    @Test
    void v4는_스키마에_endpoints를_두고_이전_항목에_주소를_붙이고_응답_주소를_정규화한다() {
        given(previousItemsReader.read(TEAM_ID)).willReturn(List.of(new PreviousItem(12L,
                Instant.parse("2026-11-19T12:00:00Z"), SummaryStatus.PARTIAL, "로그인 기능의 서버 쪽 준비",
                List.of("POST /api/auth/login"))));
        given(openAiClient.createResponse(any())).willReturn(response("""
                {"items":[{"status":"READY","title":"로그인 기능이 완성됐어요","commitShas":["%s"],
                "relatedPreviousItemId":12,"endpoints":["post /api/auth/login/","POST ${BASE_URL}/api/auth/login"]}]}"""
                .formatted(sha('b'))));

        SummaryResult result = summarizer(properties("v4", 60_000, 40, 1_000_000)).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.relatedPreviousItemId()).isEqualTo(12L);
            assertThat(item.endpoints()).containsExactly("POST /api/auth/login");
        });
        OpenAiRequest request = capturedRequest();
        assertThat(request.input()).startsWith("## 이전에 PM에게 이미 보고한 기능 (참고용. 이번 변경 아님)\n"
                + "- [P12] 11-19 21:00 🟡 로그인 기능의 서버 쪽 준비 | 주소: POST /api/auth/login\n");
        assertThat(request.instructions()).contains("[주소(endpoints)]", "${BASE_URL}/api/auth/login");
        assertThat(JSON.writeValueAsString(request)).contains(
                "\"required\":[\"status\",\"title\",\"commitShas\",\"relatedPreviousItemId\",\"endpoints\"]");
        assertThat(SummarySchema.schema(true, true)).extractingByKey("properties").satisfies(root ->
                assertThat(((Map<?, ?>) root).get("items").toString())
                        .contains("endpoints={", "type=array", "items={type=string}"));
        assertThat(result.stats().promptVersion()).isEqualTo("v4");
    }

    @Test
    void v3는_스키마와_이전_항목_줄에_주소를_넣지_않는다() {
        given(previousItemsReader.read(TEAM_ID)).willReturn(List.of(new PreviousItem(12L,
                Instant.parse("2026-11-19T12:00:00Z"), SummaryStatus.PARTIAL, "로그인 기능의 서버 쪽 준비",
                List.of("POST /api/auth/login"))));
        given(openAiClient.createResponse(any())).willReturn(response("""
                {"items":[{"status":"PARTIAL","title":"로그인 화면","commitShas":["%s"],"relatedPreviousItemId":null}]}"""
                .formatted(sha('b'))));

        SummaryResult result = summarizer(properties("v3", 60_000, 40, 1_000_000)).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.items().getFirst().endpoints()).isEmpty();
        OpenAiRequest request = capturedRequest();
        assertThat(request.input()).contains("- [P12] 11-19 21:00 🟡 로그인 기능의 서버 쪽 준비\n").doesNotContain("주소:");
        assertThat(JSON.writeValueAsString(request)).doesNotContain("endpoints");
    }

    @Test
    void v2에서_응답의_이전_항목_ID가_입력에_없으면_그_값만_버리고_요약은_성공한다() {
        given(previousItemsReader.read(TEAM_ID)).willReturn(List.of(new PreviousItem(12L,
                Instant.parse("2026-11-19T12:00:00Z"), SummaryStatus.PARTIAL, "로그인 서버")));
        given(openAiClient.createResponse(any())).willReturn(response("""
                {"items":[{"status":"PARTIAL","title":"로그인 화면","commitShas":["%s"],"relatedPreviousItemId":999}]}"""
                .formatted(sha('b'))));

        SummaryResult result = summarizer(properties("v2", 60_000, 40, 1_000_000)).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.items().getFirst().relatedPreviousItemId()).isNull();
    }

    @Test
    void v2에서_이전_항목이_없으면_섹션을_넣지_않는다() {
        given(previousItemsReader.read(TEAM_ID)).willReturn(List.of());
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"],\"relatedPreviousItemId\":null}]}".formatted(sha('a'))));

        summarizer(properties("v2", 60_000, 40, 1_000_000)).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(capturedRequest().input()).startsWith("# 레포:").doesNotContain("이전에 PM에게");
    }

    @Test
    void v1은_이전_항목을_읽지_않고_스키마도_기존과_같다() {
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));

        summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        verify(previousItemsReader, never()).read(any());
        OpenAiRequest request = capturedRequest();
        assertThat(request.input()).doesNotContain("이전에 PM에게");
        assertThat(JSON.writeValueAsString(request)).doesNotContain("relatedPreviousItemId")
                .contains("\"required\":[\"status\",\"title\",\"commitShas\"]");
        assertThat(calls.getFirst().getPromptVersion()).isEqualTo("v1");
    }

    @Test
    void 스키마_위반이면_재시도하지_않고_fallback한다() {
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"DONE\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "스키마");
        verify(openAiClient, times(1)).createResponse(any());
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.isSuccess()).isFalse();
            assertThat(call.getFailureReason()).contains("스키마");
            assertThat(call.getInputTokens()).isEqualTo(1200);   // 실패해도 과금된 토큰은 기록
        });
    }

    @Test
    void 입력에_없는_SHA가_오면_fallback한다() {
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"READY\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('f'))));

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "입력에 없는 커밋 SHA");
        assertThat(calls).singleElement().extracting(LlmCall::isSuccess).isEqualTo(false);
    }

    @Test
    void items가_비어_있으면_fallback한다() {
        given(openAiClient.createResponse(any())).willReturn(response("{\"items\":[]}"));

        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "비어 있어요");
    }

    @Test
    void 응답이_미완료면_fallback한다() {
        given(openAiClient.createResponse(any())).willReturn("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],
                 "usage":{"input_tokens":1200,"output_tokens":8000}}""");

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "max_output_tokens");
        assertThat(calls.getFirst().getOutputTokens()).isEqualTo(8000);
    }

    @Test
    void 일일_호출_상한에_도달하면_LLM을_부르지_않는다() {
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.DIGEST), any())).willReturn(40L);

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "정기 발송용 LLM 호출 상한(40회)");
        assertThat(result.limitReached()).isTrue();
        verify(openAiClient, never()).createResponse(any());
        assertThat(calls).isEmpty();
    }

    @Test
    void 일일_토큰_상한에_도달하면_LLM을_부르지_않는다() {
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.DIGEST), any())).willReturn(3L);
        given(llmCallRepository.sumTokensSince(eq(LlmCallPurpose.DIGEST), any())).willReturn(1_000_000L);

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "토큰 상한");
        verify(openAiClient, never()).createResponse(any());
    }

    @Test
    void 미리보기가_상한에_걸려도_정기_발송은_LLM을_부른다() {
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.PREVIEW), any())).willReturn(30L);
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.DIGEST), any())).willReturn(5L);
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));
        ChangeSummarizer summarizer = summarizer(Map.of(LlmCallPurpose.DIGEST, 60, LlmCallPurpose.PREVIEW, 30));

        SummaryResult preview = summarizer.summarize(TEAM_ID, LlmCallPurpose.PREVIEW);
        SummaryResult digest = summarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(preview.limitReached()).isTrue();
        assertThat(preview.fallbackReason()).contains("미리보기용 LLM 호출 상한(30회)");
        assertThat(digest.succeeded()).isTrue();
        verify(openAiClient, times(1)).createResponse(any());
        assertThat(calls).singleElement().extracting(LlmCall::getPurpose).isEqualTo(LlmCallPurpose.DIGEST);
    }

    @Test
    void 정기_발송이_상한에_걸려도_미리보기는_LLM을_부른다() {
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.DIGEST), any())).willReturn(60L);
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));
        ChangeSummarizer summarizer = summarizer(Map.of(LlmCallPurpose.DIGEST, 60, LlmCallPurpose.PREVIEW, 30));

        SummaryResult digest = summarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST);
        SummaryResult preview = summarizer.summarize(TEAM_ID, LlmCallPurpose.PREVIEW);

        assertThat(digest.limitReached()).isTrue();
        assertThat(digest.fallbackReason()).contains("정기 발송용 LLM 호출 상한(60회)");
        assertThat(preview.succeeded()).isTrue();
        verify(openAiClient, times(1)).createResponse(any());
        assertThat(calls).singleElement().extracting(LlmCall::getPurpose).isEqualTo(LlmCallPurpose.PREVIEW);
    }

    @Test
    void 토큰_상한도_용도별로_센다() {
        given(llmCallRepository.sumTokensSince(eq(LlmCallPurpose.PREVIEW), any())).willReturn(1_000_000L);
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));
        ChangeSummarizer summarizer = summarizer(40, 1_000_000);

        assertThat(summarizer.summarize(TEAM_ID, LlmCallPurpose.PREVIEW).fallbackReason()).contains("미리보기용 LLM 토큰 상한");
        assertThat(summarizer.summarize(TEAM_ID, LlmCallPurpose.DIGEST).succeeded()).isTrue();
    }

    @Test
    void 하루의_기준은_설정한_시간대의_자정이다() {
        given(openAiClient.createResponse(any())).willReturn(response("{\"items\":[]}"));

        summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        // 2026-11-20 00:00 KST
        verify(llmCallRepository).countByPurposeAndCreatedAtGreaterThanEqual(
                LlmCallPurpose.DIGEST, Instant.parse("2026-11-19T15:00:00Z"));
    }

    @Test
    void 일시적_오류는_Retry_After만큼_기다렸다가_다시_시도한다() {
        given(openAiClient.createResponse(any()))
                .willThrow(OpenAiCallException.fromStatus(429, "{\"error\":{\"message\":\"Rate limit reached\"}}", Duration.ofSeconds(3)))
                .willReturn(response("{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stats().attempts()).isEqualTo(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(3));
        assertThat(calls).extracting(LlmCall::isSuccess).containsExactly(false, true);
        assertThat(calls.getFirst().getFailureReason()).contains("429", "Rate limit reached");
    }

    @Test
    void 재시도를_모두_쓰면_fallback한다() {
        given(openAiClient.createResponse(any())).willThrow(OpenAiCallException.fromStatus(503, "", null));

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "3번 시도");
        verify(openAiClient, times(3)).createResponse(any());   // 첫 시도 + 재시도 2번
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(calls).hasSize(3).noneMatch(LlmCall::isSuccess);
    }

    @Test
    void 타임아웃도_다시_시도한다() {
        OpenAiCallException timeout = new OpenAiCallException(OpenAiCallException.Kind.TIMEOUT, null, null, null,
                "OpenAI 응답이 60초 안에 오지 않았어요", null);
        given(openAiClient.createResponse(any())).willThrow(timeout);

        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "60초");
        verify(openAiClient, times(3)).createResponse(any());
    }

    @Test
    void 재시도_중_일일_상한에_도달하면_멈춘다() {
        given(llmCallRepository.countByPurposeAndCreatedAtGreaterThanEqual(eq(LlmCallPurpose.DIGEST), any()))
                .willReturn(39L, 40L);
        given(openAiClient.createResponse(any())).willThrow(OpenAiCallException.fromStatus(500, "", null));

        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "호출 상한");
        verify(openAiClient, times(1)).createResponse(any());
    }

    @Test
    void 잘못된_요청이나_잔액_부족은_다시_시도하지_않는다() {
        given(openAiClient.createResponse(any()))
                .willThrow(OpenAiCallException.fromStatus(404,
                        "{\"error\":{\"message\":\"The model `gpt-test` does not exist\",\"code\":\"model_not_found\"}}", null));
        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "does not exist");
        verify(openAiClient, times(1)).createResponse(any());

        willThrow(OpenAiCallException.fromStatus(429, "{\"error\":{\"code\":\"insufficient_quota\"}}", null))
                .given(openAiClient).createResponse(any());
        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "한도나 잔액");
        verify(openAiClient, times(2)).createResponse(any());
        assertThat(waits).isEmpty();
    }

    @Test
    void API_키가_없으면_LLM을_부르지_않는다() {
        given(openAiClient.isConfigured()).willReturn(false);

        assertFallback(summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST), "OPENAI_API_KEY");
        verify(openAiClient, never()).createResponse(any());
    }

    @Test
    void 줄여도_입력_상한을_넘으면_LLM을_부르지_않는다() {
        SummaryResult result = summarizer(40, 1_000_000, 50).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertFallback(result, "상한(50자)");
        verify(openAiClient, never()).createResponse(any());
    }

    @Test
    void 수집된_변경이_없으면_LLM을_부르지_않는다() {
        batches.clear();

        SummaryResult result = summarizer(40, 1_000_000).summarize(TEAM_ID, LlmCallPurpose.DIGEST);

        assertThat(result.hasChanges()).isFalse();
        verify(openAiClient, never()).createResponse(any());
        verify(llmCallRepository, never()).countByPurposeAndCreatedAtGreaterThanEqual(any(), any());
    }

    @Test
    void 미리보기는_PREVIEW로_기록하고_배치를_건드리지_않는다() {
        given(teamRepository.findByDiscordChannelId(100L)).willReturn(Optional.of(team()));
        given(openAiClient.createResponse(any())).willReturn(response(
                "{\"items\":[{\"status\":\"PARTIAL\",\"title\":\"로그인\",\"commitShas\":[\"%s\"]}]}".formatted(sha('a'))));

        SummaryPreview preview = summarizer(40, 1_000_000).previewByChannel(100L);

        assertThat(preview.teamName()).isEqualTo("1팀");
        assertThat(preview.result().succeeded()).isTrue();
        assertThat(calls).singleElement().extracting(LlmCall::getPurpose).isEqualTo(LlmCallPurpose.PREVIEW);
        assertThat(batches).allMatch(b -> b.getStatus() == ChangeBatchStatus.COLLECTED);
        verify(changeBatchRepository, never()).save(any());
    }

    @Test
    void 팀_채널이_아니면_미리보기를_거절한다() {
        given(teamRepository.findByDiscordChannelId(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarizer(40, 1_000_000).previewByChannel(999L))
                .isInstanceOf(IllegalStateException.class);
        verify(openAiClient, never()).createResponse(any());
    }

    @Test
    void 대기_시간은_최대_10초다() {
        assertThat(ChangeSummarizer.backoff(0, Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(10));
        assertThat(ChangeSummarizer.backoff(0, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ChangeSummarizer.backoff(1, null)).isEqualTo(Duration.ofSeconds(2));
    }

    // ---- helpers ----

    private ChangeSummarizer summarizer(int dailyMaxCalls, long dailyMaxTokens) {
        return summarizer(dailyMaxCalls, dailyMaxTokens, 60_000);
    }

    private ChangeSummarizer summarizer(Map<LlmCallPurpose, Integer> dailyMaxCalls) {
        SummaryProperties base = properties(60_000, 1, 1_000_000);
        return summarizer(new SummaryProperties(base.promptVersion(), base.maxInputChars(), dailyMaxCalls,
                Map.of(LlmCallPurpose.DIGEST, 1_000_000L, LlmCallPurpose.PREVIEW, 1_000_000L), base.zone(),
                base.excludedFiles(), base.patchOmittedFiles(), base.previousItems()));
    }

    private ChangeSummarizer summarizer(int dailyMaxCalls, long dailyMaxTokens, int maxInputChars) {
        return summarizer(properties(maxInputChars, dailyMaxCalls, dailyMaxTokens));
    }

    private ChangeSummarizer summarizer(SummaryProperties summaryProperties) {
        OpenAiProperties openAiProperties = new OpenAiProperties("test-key", "http://localhost", "gpt-test", "low",
                Duration.ofSeconds(60), 2, 8000);
        return new ChangeSummarizer(changeBatchRepository, teamRepository, llmCallRepository, openAiClient,
                new SummaryInputBuilder(summaryProperties), previousItemsReader, new SummaryPrompt(summaryProperties),
                new SummaryResponseParser(JSON), openAiProperties, summaryProperties,
                Clock.fixed(NOW, ZoneOffset.UTC), waits::add);
    }

    private OpenAiRequest capturedRequest() {
        ArgumentCaptor<OpenAiRequest> captor = ArgumentCaptor.forClass(OpenAiRequest.class);
        verify(openAiClient).createResponse(captor.capture());
        return captor.getValue();
    }

    private static String response(String outputText) {
        return JSON.writeValueAsString(Map.of(
                "id", "resp_1",
                "status", "completed",
                "output", List.of(
                        Map.of("type", "reasoning", "summary", List.of()),
                        Map.of("type", "message", "role", "assistant", "content", List.of(
                                Map.of("type", "output_text", "text", outputText, "annotations", List.of())))),
                "usage", Map.of("input_tokens", 1200, "output_tokens", 150, "total_tokens", 1350)));
    }

    private static void assertFallback(SummaryResult result, String reasonPart) {
        assertThat(result.hasChanges()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.items()).isEmpty();
        assertThat(result.fallbackReason()).contains(reasonPart);
    }
}
