package discord.moa.discordmoa.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 오류 응답 본문: {"error": {"message": ..., "type": ..., "code": ...}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiErrorResponse(Error error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String message, String type, String code) {
    }
}
