package discord.moa.discordmoa.global.jpa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StringListJsonConverterTest {

    private final StringListJsonConverter converter = new StringListJsonConverter();

    @Test
    void 목록은_JSON_배열_문자열로_저장하고_다시_읽는다() {
        String json = converter.convertToDatabaseColumn(List.of("POST /api/auth/login", "GET /api/users/{id}"));

        assertThat(json).isEqualTo("[\"POST /api/auth/login\",\"GET /api/users/{id}\"]");
        assertThat(converter.convertToEntityAttribute(json)).containsExactly("POST /api/auth/login", "GET /api/users/{id}");
    }

    @Test
    void 빈_목록은_null로_저장하고_null은_빈_목록으로_읽는다() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
