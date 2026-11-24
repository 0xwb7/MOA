package discord.moa.discordmoa.domain.collect.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClassPathExtractorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "@RequestMapping(\"/api/auth\")",
            "@RequestMapping(value = \"/api/auth\")",
            "@RequestMapping(path = \"/api/auth\", produces = \"application/json\")",
            "@RequestMapping(produces = \"application/json\", value = \"/api/auth\")",
            "@RequestMapping({\"/api/auth\", \"/v2/auth\"})",
            "@RequestMapping(value = {\"/api/auth\", \"/v2/auth\"})",
            "@RequestMapping(\"api/auth/\")",
            "@org.springframework.web.bind.annotation.RequestMapping(\"/api/auth\")",
    })
    void Java_클래스_레벨_경로를_뽑는다(String annotation) {
        String source = """
                package moa.auth;

                import org.springframework.web.bind.annotation.*;

                @RestController
                %s
                public class AuthController {

                    @PostMapping("/signup")
                    public void signup() {}
                }
                """.formatted(annotation);

        assertThat(ClassPathExtractor.extract(source)).isEqualTo("/api/auth");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@RequestMapping(\"/api/auth\")",
            "@RequestMapping([\"/api/auth\"])",
            "@RequestMapping(value = [\"/api/auth\", \"/v2/auth\"])",
            "@RequestMapping(path = [\"/api/auth\"])",
    })
    void Kotlin_클래스_레벨_경로를_뽑는다(String annotation) {
        String source = """
                package moa.auth

                @RestController
                %s
                class AuthController(private val authService: AuthService) {
                    @PostMapping("/signup")
                    fun signup(@RequestBody request: SignupRequest) = authService.signup(request)
                }
                """.formatted(annotation);

        assertThat(ClassPathExtractor.extract(source)).isEqualTo("/api/auth");
    }

    @Test
    void 클래스_레벨_매핑이_없으면_null이다() {
        assertThat(ClassPathExtractor.extract("""
                @RestController
                public class HealthController {
                    @RequestMapping("/health")
                    public String health() { return "ok"; }
                }
                """)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@RequestMapping(ApiPaths.AUTH)",
            "@RequestMapping(value = ApiPaths.AUTH)",
            "@RequestMapping({ApiPaths.AUTH})",
            "@RequestMapping(produces = \"application/json\")",
            "@RequestMapping",
            "@RequestMapping(\"${api.prefix}/auth\")",
            "@RequestMapping(\"/\")",
            "// @RequestMapping(\"/api/old\")",
            "/* @RequestMapping(\"/api/old\") */",
    })
    void 경로를_알_수_없으면_null이다(String annotation) {
        String source = "@RestController\n" + annotation + "\npublic class AuthController {}";

        assertThat(ClassPathExtractor.extract(source)).isNull();
    }

    @Test
    void 문자열_안의_주석_기호와_괄호는_그대로_둔다() {
        String source = """
                // 설명 주석: @RequestMapping("/wrong")
                @RequestMapping(value = "/api/files(v2)//", produces = "text/plain")
                class FileController {}
                """;

        assertThat(ClassPathExtractor.extract(source)).isEqualTo("/api/files(v2)");
    }

    @Test
    void 소스가_없거나_클래스가_없으면_null이다() {
        assertThat(ClassPathExtractor.extract(null)).isNull();
        assertThat(ClassPathExtractor.extract("")).isNull();
        assertThat(ClassPathExtractor.extract("@RequestMapping(\"/api\")")).isNull();
    }
}
