package discord.moa.discordmoa.global.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 문자열 목록을 VARCHAR 컬럼에 JSON 배열 문자열(["a","b"])로 저장한다. commitShas처럼 JSON 형식이지만,
 * 길이 상한이 있는 VARCHAR가 필요할 때 쓴다. 빈 목록은 null로 저장해 "값 없음"을 한 가지로 다룬다.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return JsonMapper.shared().writeValueAsString(values);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JsonMapper.shared().readValue(json, STRING_LIST));
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 문자열 목록을 읽지 못했어요: " + json, e);
        }
    }
}
