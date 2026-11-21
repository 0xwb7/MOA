package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.summary.entity.LlmCall;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.repository.LlmCallRepository;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.global.config.OpenAiProperties;
import discord.moa.discordmoa.global.config.SummaryProperties;
import discord.moa.discordmoa.openai.client.OpenAiCallException;
import discord.moa.discordmoa.openai.client.OpenAiClient;
import discord.moa.discordmoa.openai.dto.OpenAiRequest;
import discord.moa.discordmoa.openai.dto.OpenAiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 팀의 COLLECTED 배치를 LLM으로 요약한다. 트랜잭션 밖에서 호출해야 한다.
 * 어떤 이유로든 요약을 못 만들면 예외 대신 fallback 결과(사유 포함)를 돌려준다.
 */
@Slf4j
@Service
public class ChangeSummarizer {

    static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(10);

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ChangeBatchRepository changeBatchRepository;
    private final TeamRepository teamRepository;
    private final LlmCallRepository llmCallRepository;
    private final OpenAiClient openAiClient;
    private final SummaryInputBuilder inputBuilder;
    private final SummaryPrompt prompt;
    private final SummaryResponseParser parser;
    private final OpenAiProperties openAiProperties;
    private final SummaryProperties summaryProperties;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public ChangeSummarizer(ChangeBatchRepository changeBatchRepository, TeamRepository teamRepository,
                            LlmCallRepository llmCallRepository, OpenAiClient openAiClient,
                            SummaryInputBuilder inputBuilder, SummaryPrompt prompt, SummaryResponseParser parser,
                            OpenAiProperties openAiProperties, SummaryProperties summaryProperties) {
        this(changeBatchRepository, teamRepository, llmCallRepository, openAiClient, inputBuilder, prompt, parser,
                openAiProperties, summaryProperties, Clock.systemUTC(), Thread::sleep);
    }

    ChangeSummarizer(ChangeBatchRepository changeBatchRepository, TeamRepository teamRepository,
                     LlmCallRepository llmCallRepository, OpenAiClient openAiClient,
                     SummaryInputBuilder inputBuilder, SummaryPrompt prompt, SummaryResponseParser parser,
                     OpenAiProperties openAiProperties, SummaryProperties summaryProperties,
                     Clock clock, Sleeper sleeper) {
        this.changeBatchRepository = changeBatchRepository;
        this.teamRepository = teamRepository;
        this.llmCallRepository = llmCallRepository;
        this.openAiClient = openAiClient;
        this.inputBuilder = inputBuilder;
        this.prompt = prompt;
        this.parser = parser;
        this.openAiProperties = openAiProperties;
        this.summaryProperties = summaryProperties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * /요약미리보기: 요약만 만든다. Digest를 만들거나 배치 상태를 바꾸지 않는다 (LlmCall 기록은 남긴다).
     */
    public SummaryPreview previewByChannel(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        return new SummaryPreview(team.getName(), summarize(team.getId(), LlmCallPurpose.PREVIEW));
    }

    public SummaryResult summarize(Long teamId, LlmCallPurpose purpose) {
        List<ChangeBatch> batches = changeBatchRepository
                .findAllByTrackedRepoTeamIdAndStatusOrderByIdAsc(teamId, ChangeBatchStatus.COLLECTED);
        if (batches.isEmpty()) {
            return SummaryResult.noChanges();
        }
        List<Long> batchIds = batches.stream().map(ChangeBatch::getId).toList();
        Attempts attempts = new Attempts(teamId, purpose);

        if (!openAiClient.isConfigured()) {
            return fallback(attempts, batchIds, "OPENAI_API_KEY가 설정되지 않았어요");
        }

        SummaryInput input = inputBuilder.build(batches);
        attempts.inputChars = input.text().length();
        if (input.droppedPatchCount() > 0 || input.messagesShortened()) {
            log.info("LLM 입력을 줄였어요: teamId={}, patch 제외 파일 {}개, 메시지 첫 줄만 {}, 최종 {}자",
                    teamId, input.droppedPatchCount(), input.messagesShortened(), attempts.inputChars);
        }
        if (input.overLimit()) {
            return fallback(attempts, batchIds, "입력을 줄여도 " + attempts.inputChars + "자라서 상한("
                    + summaryProperties.maxInputChars() + "자)을 넘어요");
        }

        OpenAiRequest request = request(input.text());
        int maxRetries = Math.max(0, openAiProperties.maxRetries());
        for (int attempt = 0; ; attempt++) {
            String capReason = dailyCapExceeded();
            if (capReason != null) {
                return fallback(attempts, batchIds, capReason);
            }

            attempts.count++;
            long start = clock.millis();
            String body;
            try {
                body = openAiClient.createResponse(request);
            } catch (OpenAiCallException e) {
                long elapsed = attempts.elapsedSince(start);
                String reason = describe(e);
                record(attempts, elapsed, null, null, false, reason, e.getResponseBody());

                if (e.isRetryable() && attempt < maxRetries) {
                    Duration wait = backoff(attempt, e.getRetryAfter());
                    log.warn("OpenAI 호출 실패, {}ms 후 다시 시도해요: teamId={}, attempt={}, reason={}",
                            wait.toMillis(), teamId, attempts.count, reason);
                    try {
                        sleeper.sleep(wait);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return fallback(attempts, batchIds, "재시도 대기 중 중단됐어요");
                    }
                    continue;
                }
                return fallback(attempts, batchIds, reason
                        + (e.isRetryable() ? " (" + attempts.count + "번 시도)" : ""));
            } catch (RuntimeException e) {
                long elapsed = attempts.elapsedSince(start);
                String reason = "OpenAI 호출 중 예상하지 못한 오류: " + e.getMessage();
                record(attempts, elapsed, null, null, false, reason, null);
                log.error("OpenAI 호출 중 예상하지 못한 오류: teamId={}", teamId, e);
                return fallback(attempts, batchIds, reason);
            }

            long elapsed = attempts.elapsedSince(start);
            Integer inputTokens = null;
            Integer outputTokens = null;
            try {
                OpenAiResponse response = parser.readEnvelope(body);
                if (response.usage() != null) {
                    inputTokens = response.usage().inputTokens();
                    outputTokens = response.usage().outputTokens();
                    attempts.addTokens(inputTokens, outputTokens);
                }
                List<SummaryItem> items = parser.parseItems(parser.outputText(response), input.knownShas());
                record(attempts, elapsed, inputTokens, outputTokens, true, null, body);
                return SummaryResult.success(batchIds, items, attempts.stats());
            } catch (SummaryValidationException e) {
                record(attempts, elapsed, inputTokens, outputTokens, false, e.getMessage(), body);
                return fallback(attempts, batchIds, "응답 검증 실패: " + e.getMessage());
            }
        }
    }

    private OpenAiRequest request(String input) {
        String effort = openAiProperties.reasoningEffort();
        return new OpenAiRequest(
                openAiProperties.model(),
                prompt.text(),
                input,
                effort == null || effort.isBlank() ? null : new OpenAiRequest.Reasoning(effort),
                openAiProperties.maxOutputTokens(),
                new OpenAiRequest.Text(OpenAiRequest.Format.jsonSchema(SummarySchema.NAME, SummarySchema.schema())),
                false
        );
    }

    private String dailyCapExceeded() {
        Instant since = LocalDate.now(clock.withZone(summaryProperties.zone()))
                .atStartOfDay(summaryProperties.zone())
                .toInstant();
        long calls = llmCallRepository.countByCreatedAtGreaterThanEqual(since);
        if (calls >= summaryProperties.dailyMaxCalls()) {
            return "오늘 LLM 호출 상한(" + summaryProperties.dailyMaxCalls() + "회)에 도달했어요";
        }
        long tokens = llmCallRepository.sumTokensSince(since);
        if (tokens >= summaryProperties.dailyMaxTokens()) {
            return "오늘 LLM 토큰 상한(" + summaryProperties.dailyMaxTokens() + ")에 도달했어요 (사용 " + tokens + ")";
        }
        return null;
    }

    static Duration backoff(int attempt, Duration retryAfter) {
        Duration wait = retryAfter != null ? retryAfter : BASE_BACKOFF.multipliedBy(1L << attempt);
        if (wait.isNegative()) {
            return Duration.ZERO;
        }
        return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
    }

    private String describe(OpenAiCallException e) {
        String detail = parser.errorMessage(e.getResponseBody());
        String reason = switch (e.getKind()) {
            case QUOTA -> "OpenAI 사용 한도나 잔액이 부족해요";
            case CLIENT -> e.getStatus() != null && e.getStatus() == 401
                    ? "OpenAI API 키가 올바르지 않아요"
                    : e.getMessage();
            default -> e.getMessage();
        };
        return detail == null ? reason : reason + ": " + detail;
    }

    private SummaryResult fallback(Attempts attempts, List<Long> batchIds, String reason) {
        log.warn("LLM 요약 실패, 변경 목록(fallback)으로 대체해요: teamId={}, purpose={}, reason={}",
                attempts.teamId, attempts.purpose, reason);
        return SummaryResult.fallback(batchIds, reason, attempts.stats());
    }

    private void record(Attempts attempts, long durationMs, Integer inputTokens, Integer outputTokens,
                        boolean success, String failureReason, String responseBody) {
        llmCallRepository.save(LlmCall.builder()
                .teamId(attempts.teamId)
                .purpose(attempts.purpose)
                .promptVersion(prompt.version())
                .model(openAiProperties.model())
                .inputChars(attempts.inputChars)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .durationMs(durationMs)
                .success(success)
                .failureReason(failureReason)
                .responseBody(responseBody)
                .createdAt(clock.instant())
                .build());
    }

    /**
     * 한 번의 summarize 동안 시도 횟수, 토큰, 소요 시간을 모은다.
     */
    private final class Attempts {
        private final Long teamId;
        private final LlmCallPurpose purpose;
        private int inputChars;
        private int count;
        private int inputTokens;
        private int outputTokens;
        private long durationMs;

        private Attempts(Long teamId, LlmCallPurpose purpose) {
            this.teamId = teamId;
            this.purpose = purpose;
        }

        private long elapsedSince(long start) {
            long elapsed = Math.max(0, clock.millis() - start);
            durationMs += elapsed;
            return elapsed;
        }

        private void addTokens(Integer input, Integer output) {
            inputTokens += input == null ? 0 : input;
            outputTokens += output == null ? 0 : output;
        }

        private SummaryStats stats() {
            return new SummaryStats(prompt.version(), openAiProperties.model(), inputChars,
                    inputTokens, outputTokens, durationMs, count);
        }
    }
}
