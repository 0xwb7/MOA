package discord.moa.discordmoa.openai.client;

import discord.moa.discordmoa.global.config.OpenAiProperties;
import discord.moa.discordmoa.openai.dto.OpenAiRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI Responses API를 SDK 없이 RestClient로 호출한다.
 * 성공하면 응답 본문(JSON 문자열)을 그대로 돌려주고, 해석은 호출하는 쪽에서 한다.
 */
@Component
public class OpenAiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final OpenAiProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public OpenAiClient(OpenAiProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    public String createResponse(OpenAiRequest request) {
        String json = jsonMapper.writeValueAsString(request);
        try {
            return restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int status = res.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            return body;
                        }
                        throw OpenAiCallException.fromStatus(status, body,
                                retryAfter(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
                    });
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException
                    || e.getCause() instanceof InterruptedIOException;
            OpenAiCallException.Kind kind = timeout ? OpenAiCallException.Kind.TIMEOUT : OpenAiCallException.Kind.NETWORK;
            String message = timeout
                    ? "OpenAI 응답이 " + properties.timeout().toSeconds() + "초 안에 오지 않았어요"
                    : "OpenAI에 연결하지 못했어요: " + e.getMessage();
            throw new OpenAiCallException(kind, null, null, null, message, e);
        }
    }

    static Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(header.strip()) * 1000));
        } catch (NumberFormatException e) {
            return null;   // HTTP-date 형식은 쓰지 않고 기본 대기 시간을 쓴다
        }
    }
}
