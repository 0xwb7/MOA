package discord.moa.discordmoa.openai.client;

import com.sun.net.httpserver.HttpServer;
import discord.moa.discordmoa.global.config.OpenAiProperties;
import discord.moa.discordmoa.openai.dto.OpenAiRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 로컬 HTTP 서버를 OpenAI 대신 세워 실제 HTTP 요청·응답 처리를 확인한다.
 */
class OpenAiClientTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 성공하면_응답_본문을_그대로_돌려준다() throws IOException {
        OpenAiClient client = client(200, "{\"status\":\"completed\"}", Map.of(), 0, Duration.ofSeconds(5));

        String body = client.createResponse(request(null));

        assertThat(body).isEqualTo("{\"status\":\"completed\"}");
        assertThat(path.get()).isEqualTo("/v1/responses");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"gpt-test\"", "\"max_output_tokens\":100", "\"store\":false",
                        "\"type\":\"json_schema\"", "\"strict\":true")
                .doesNotContain("reasoning", "null");
    }

    @Test
    void 레이트_리밋은_Retry_After와_함께_다시_시도할_수_있는_오류다() throws IOException {
        OpenAiClient client = client(429, "{\"error\":{\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}",
                Map.of("Retry-After", "2"), 0, Duration.ofSeconds(5));

        OpenAiCallException e = call(client);

        assertThat(e.getKind()).isEqualTo(OpenAiCallException.Kind.RATE_LIMIT);
        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(e.getStatus()).isEqualTo(429);
    }

    @Test
    void 잔액_부족_429는_다시_시도하지_않는다() throws IOException {
        OpenAiCallException e = call(client(429, "{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}",
                Map.of(), 0, Duration.ofSeconds(5)));

        assertThat(e.getKind()).isEqualTo(OpenAiCallException.Kind.QUOTA);
        assertThat(e.isRetryable()).isFalse();
    }

    @Test
    void 서버_오류는_다시_시도하고_요청_오류는_다시_시도하지_않는다() throws IOException {
        assertThat(call(client(503, "", Map.of(), 0, Duration.ofSeconds(5))).isRetryable()).isTrue();
        server.stop(0);
        OpenAiCallException badRequest = call(client(400, "{\"error\":{\"message\":\"Invalid schema\"}}", Map.of(), 0, Duration.ofSeconds(5)));
        assertThat(badRequest.getKind()).isEqualTo(OpenAiCallException.Kind.CLIENT);
        assertThat(badRequest.getResponseBody()).contains("Invalid schema");
    }

    @Test
    void 응답이_늦으면_타임아웃으로_구분한다() throws IOException {
        OpenAiCallException e = call(client(200, "{}", Map.of(), 1500, Duration.ofMillis(300)));

        assertThat(e.getKind()).isEqualTo(OpenAiCallException.Kind.TIMEOUT);
        assertThat(e.isRetryable()).isTrue();
    }

    @Test
    void API_키가_비어_있으면_설정되지_않은_것으로_본다() {
        JsonMapper json = JsonMapper.builder().build();
        assertThat(new OpenAiClient(properties("", "http://localhost", Duration.ofSeconds(1)), json).isConfigured()).isFalse();
        assertThat(new OpenAiClient(properties(null, "http://localhost", Duration.ofSeconds(1)), json).isConfigured()).isFalse();
    }

    @Test
    void Retry_After는_초_단위만_해석한다() {
        assertThat(OpenAiClient.retryAfter("1.5")).isEqualTo(Duration.ofMillis(1500));
        assertThat(OpenAiClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(OpenAiClient.retryAfter(null)).isNull();
    }

    // ---- helpers ----

    private OpenAiClient client(int status, String body, Map<String, String> headers, long delayMs, Duration timeout)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException ignored) {
                // 타임아웃 테스트에서는 클라이언트가 먼저 끊는다
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new OpenAiClient(properties("test-key", baseUrl, timeout), JsonMapper.builder().build());
    }

    private static OpenAiProperties properties(String apiKey, String baseUrl, Duration timeout) {
        return new OpenAiProperties(apiKey, baseUrl, "gpt-test", null, timeout, 2, 100);
    }

    private static OpenAiRequest request(String effort) {
        return new OpenAiRequest("gpt-test", "지시문", "입력",
                effort == null ? null : new OpenAiRequest.Reasoning(effort), 100,
                new OpenAiRequest.Text(OpenAiRequest.Format.jsonSchema("pm_summary",
                        Map.of("type", "object", "additionalProperties", false))), false);
    }

    private static OpenAiCallException call(OpenAiClient client) {
        return catchThrowableOfType(OpenAiCallException.class, () -> client.createResponse(request(null)));
    }
}
