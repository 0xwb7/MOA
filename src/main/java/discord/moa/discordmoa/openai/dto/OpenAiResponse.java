package discord.moa.discordmoa.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Responses API 응답에서 필요한 필드만 읽는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiResponse(
        String status,
        @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
        List<Output> output,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncompleteDetails(String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(String type, List<Content> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String type, String text, String refusal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens
    ) {
    }
}
