package discord.moa.discordmoa.domain.summary.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "post /api/auth/login/           | POST /api/auth/login",
            "POST /api/auth/login            | POST /api/auth/login",
            "  Get   /api/posts?page=1&size=10 | GET /api/posts",
            "GET /api/posts#top              | GET /api/posts",
            "GET https://api.moa.dev/api/me  | GET /api/me",
            "POST ${BASE_URL}/api/auth/login | POST /api/auth/login",
            "GET ${API}/api/users/${userId}  | GET /api/users/{userId}",
            "DELETE /api/users/:id           | DELETE /api/users/{id}",
            "GET /api/users/{id}             | GET /api/users/{id}",
            "GET api//posts///               | GET /api/posts",
            "GET /                           | GET /",
    })
    void 메서드는_대문자로_주소는_path만_남긴다(String raw, String expected) {
        assertThat(Endpoints.normalizeOne(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "/api/auth/login", "LOGIN /api/auth/login", "POST", "POST /api/a b",
            "POST /api/\"x\"", "POST /api/`x`"})
    void 형식이_다르면_버린다(String raw) {
        assertThat(Endpoints.normalizeOne(raw)).isNull();
    }

    @Test
    void 정규화한_뒤_같은_주소는_하나만_남기고_깨진_항목은_버린다() {
        List<String> raw = new ArrayList<>();
        raw.add("post /api/auth/login/");
        raw.add("POST /api/auth/login?redirect=/home");
        raw.add("이상한 값");
        raw.add(null);
        raw.add("GET /api/me");

        assertThat(Endpoints.normalize(raw)).containsExactly("POST /api/auth/login", "GET /api/me");
        assertThat(Endpoints.normalize(null)).isEmpty();
        assertThat(Endpoints.normalize(List.of())).isEmpty();
    }

    @Test
    void JSON_배열로_저장했을_때_500자를_넘지_않는_만큼만_남긴다() {
        List<String> raw = IntStream.range(0, 40).mapToObj(i -> "GET /api/resources/number-" + i).toList();

        List<String> kept = Endpoints.normalize(raw);

        String json = "[" + String.join(",", kept.stream().map(e -> "\"" + e + "\"").toList()) + "]";
        assertThat(json.length()).isLessThanOrEqualTo(Endpoints.MAX_STORED_LENGTH);
        assertThat(kept).hasSizeLessThan(40).startsWith("GET /api/resources/number-0");
        assertThat(new discord.moa.discordmoa.global.jpa.StringListJsonConverter().convertToDatabaseColumn(kept))
                .isEqualTo(json);
    }
}
