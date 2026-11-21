package discord.moa.discordmoa.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Responses API(POST /v1/responses) 요청 본문.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiRequest(
        String model,
        String instructions,
        String input,
        Reasoning reasoning,
        @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        Text text,
        Boolean store            // false: OpenAI 쪽에 응답을 저장하지 않음
) {

    public record Reasoning(String effort) {
    }

    public record Text(Format format) {
    }

    public record Format(String type, String name, Map<String, Object> schema, boolean strict) {

        public static Format jsonSchema(String name, Map<String, Object> schema) {
            return new Format("json_schema", name, schema, true);
        }
    }
}
