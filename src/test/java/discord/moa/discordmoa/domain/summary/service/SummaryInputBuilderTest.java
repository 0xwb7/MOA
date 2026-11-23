package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.batch;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.commit;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.file;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.properties;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.repo;
import static discord.moa.discordmoa.domain.summary.service.SummaryFixtures.sha;
import static org.assertj.core.api.Assertions.assertThat;

class SummaryInputBuilderTest {

    private static final int NO_LIMIT = 1_000_000;

    private final TrackedRepo backend = repo(1L, "team1/backend");
    private final TrackedRepo frontend = repo(2L, "team1/frontend");

    @ParameterizedTest
    @ValueSource(strings = {
            ".env", "server/.env", ".env.local", "web/.env.production", ".ENV",
            "certs/app.pem", "Config/App.PEM", "keys/private.key", "keystore.p12", "app.jks",
            "src/main/resources/application-local.yml", "src/main/resources/application-secret.yml",
            "src/main/resources/application-my-local.yaml",
            "credentials", "config/credentials.json", "gcp-credentials.json", "client_secret.json"
    })
    void 비밀_파일은_제외된다(String filename) {
        assertThat(builder(NO_LIMIT).isExcluded(filename)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/resources/application.yml", "src/main/resources/application-prod.yml",
            "src/main/java/team/auth/ClientSecretService.java", "src/env/EnvConfig.java", "keyboard.tsx"
    })
    void 비밀_파일이_아니면_제외하지_않는다(String filename) {
        assertThat(builder(NO_LIMIT).isExcluded(filename)).isFalse();
    }

    @Test
    void 비밀_파일은_이름도_patch도_입력에_넣지_않는다() {
        ChangeBatch batch = batch(1L, backend, List.of(commit(sha('a'), "로그인 API 추가")), List.of(
                file(".env", "+DB_PASSWORD=SECRET_VALUE_1"),
                file("certs/server.pem", "+-----BEGIN PRIVATE KEY----- SECRET_VALUE_2"),
                file("src/main/resources/application-local.yml", "+password: SECRET_VALUE_3"),
                file("src/main/java/LoginController.java", "+@PostMapping(\"/login\")")
        ));

        SummaryInput input = builder(NO_LIMIT).build(List.of(batch));

        assertThat(input.text())
                .doesNotContain(".env", "server.pem", "application-local.yml")
                .doesNotContain("SECRET_VALUE")
                .contains("LoginController.java (modified, +10 -2)", "@PostMapping(\"/login\")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "package-lock.json", "web/yarn.lock", "pnpm-lock.yaml", "Gemfile.lock",
            "build/libs/app.jar", "web/dist/main.js", "out/production/A.class",
            "node_modules/react/index.js", ".idea/workspace.xml", "web/.vscode/settings.json"
    })
    void lock_파일과_빌드_산출물은_patch를_생략한다(String filename) {
        assertThat(builder(NO_LIMIT).isPatchOmitted(filename)).isTrue();
        assertThat(builder(NO_LIMIT).isExcluded(filename)).isFalse();
    }

    @Test
    void lock_파일과_patch_없는_파일은_이름과_줄_수만_넣는다() {
        ChangeBatch batch = batch(1L, frontend, List.of(commit(sha('a'), "로그인 화면")), List.of(
                file("package-lock.json", "+LOCK_PATCH_CONTENT"),
                file("dist/bundle.js", "+BUNDLE_PATCH_CONTENT"),
                new FileChange(null, "public/logo.png", "added", 0, 0, null, false),
                file("src/pages/Login.tsx", "+<LoginForm />")
        ));

        SummaryInput input = builder(NO_LIMIT).build(List.of(batch));

        assertThat(input.text())
                .contains("- package-lock.json (modified, +10 -2) [diff 생략: 자동 생성 파일]")
                .contains("- dist/bundle.js (modified, +10 -2) [diff 생략: 자동 생성 파일]")
                .contains("- public/logo.png (added, +0 -0) [diff 없음]")
                .contains("<LoginForm />")
                .doesNotContain("LOCK_PATCH_CONTENT", "BUNDLE_PATCH_CONTENT");
    }

    @Test
    void 레포별로_커밋_SHA와_전체_메시지_파일을_담는다() {
        ChangeBatch first = batch(1L, backend, List.of(commit(sha('a'), "로그인 API 추가\n\n- JWT 발급")),
                List.of(new FileChange(sha('a'), "src/AuthService.java", "added", 40, 0, "+class AuthService", false)));
        ChangeBatch second = batch(2L, backend, List.of(commit(sha('b'), "버그 수정")), List.of());
        ChangeBatch front = batch(3L, frontend, List.of(commit(sha('c'), "로그인 화면")), List.of());

        SummaryInput input = builder(NO_LIMIT).build(List.of(first, second, front));

        assertThat(input.text()).containsOnlyOnce("# 레포: team1/backend");
        assertThat(input.text()).contains(
                "# 레포: team1/frontend",
                "- SHA: " + sha('a') + "\n  메시지:\n    로그인 API 추가\n    \n    - JWT 발급\n",
                "- src/AuthService.java (added, +40 -0, 커밋 " + sha('a') + ")\n<diff>\n+class AuthService\n</diff>\n");
        assertThat(input.knownShas()).containsExactly(sha('a'), sha('b'), sha('c'));
        assertThat(input.droppedPatchCount()).isZero();
        assertThat(input.messagesShortened()).isFalse();
        assertThat(input.overLimit()).isFalse();
    }

    @Test
    void 상한을_넘으면_가장_큰_patch부터_뺀다() {
        String big = "+" + "B".repeat(5000);
        String medium = "+" + "M".repeat(1000);
        String small = "+" + "S".repeat(100);
        List<ChangeBatch> batches = List.of(batch(1L, backend, List.of(commit(sha('a'), "제목\n본문 설명")),
                List.of(file("Small.java", small), file("Big.java", big), file("Medium.java", medium))));
        int full = builder(NO_LIMIT).build(batches).text().length();

        SummaryInput input = builder(full - 3000).build(batches);

        assertThat(input.droppedPatchCount()).isEqualTo(1);
        assertThat(input.text())
                .doesNotContain(big)
                .contains("- Big.java (modified, +10 -2) [diff 생략: 입력 크기 제한]")
                .contains(medium, small, "본문 설명");
        assertThat(input.messagesShortened()).isFalse();
        assertThat(input.overLimit()).isFalse();
        assertThat(input.text().length()).isLessThanOrEqualTo(full - 3000);
    }

    @Test
    void 상한을_넘으면_큰_patch부터_차례로_빼고_필요한_만큼만_뺀다() {
        String big = "+" + "B".repeat(5000);
        String medium = "+" + "M".repeat(3000);
        String small = "+" + "S".repeat(100);
        List<ChangeBatch> batches = List.of(batch(1L, backend, List.of(commit(sha('a'), "제목")),
                List.of(file("Small.java", small), file("Big.java", big), file("Medium.java", medium))));
        int full = builder(NO_LIMIT).build(batches).text().length();

        SummaryInput input = builder(full - 6000).build(batches);

        assertThat(input.droppedPatchCount()).isEqualTo(2);
        assertThat(input.text()).doesNotContain(big, medium).contains(small);
        assertThat(input.messagesShortened()).isFalse();
    }

    @Test
    void patch를_모두_빼도_넘으면_커밋_메시지를_첫_줄만_남긴다() {
        String longBody = "상세 설명 ".repeat(500);
        List<ChangeBatch> batches = List.of(
                batch(1L, backend, List.of(commit(sha('a'), "로그인 API 추가\n\n" + longBody)),
                        List.of(file("A.java", "+" + "A".repeat(500)))),
                batch(2L, frontend, List.of(commit(sha('b'), "로그인 화면 추가\n\n" + longBody)),
                        List.of(file("B.tsx", "+" + "B".repeat(500)))));

        SummaryInput input = builder(1500).build(batches);

        assertThat(input.droppedPatchCount()).isEqualTo(2);
        assertThat(input.messagesShortened()).isTrue();
        assertThat(input.overLimit()).isFalse();
        assertThat(input.text())
                .contains("로그인 API 추가", "로그인 화면 추가", "A.java", "B.tsx")
                .doesNotContain("상세 설명");
        assertThat(input.knownShas()).containsExactly(sha('a'), sha('b'));
    }

    @Test
    void 줄여도_상한을_넘으면_overLimit이다() {
        List<ChangeBatch> batches = List.of(batch(1L, backend,
                List.of(commit(sha('a'), "로그인 API 추가"), commit(sha('b'), "회원가입 API 추가")),
                List.of(file("A.java", "+a"))));

        SummaryInput input = builder(50).build(batches);

        assertThat(input.overLimit()).isTrue();
        assertThat(input.messagesShortened()).isTrue();
    }

    // ---- 이전에 보고한 항목 (v2) ----

    private static final PreviousItem P12 = new PreviousItem(12L, Instant.parse("2026-11-23T00:00:00Z"),
            SummaryStatus.PARTIAL, "로그인 서버 기능이 준비됐어요 (화면 연결 전)");
    private static final PreviousItem P15 = new PreviousItem(15L, Instant.parse("2026-11-23T12:00:00Z"),
            SummaryStatus.READY, "회원가입을 직접 써볼 수 있어요");
    private static final PreviousItem P18 = new PreviousItem(18L, Instant.parse("2026-11-24T00:00:00Z"),
            SummaryStatus.PARTIAL, "비밀번호 찾기 화면이 생겼어요 (서버는 아직이에요)");

    @Test
    void 이전_항목을_이번_변경_앞에_참고_섹션으로_넣는다() {
        List<ChangeBatch> batches = List.of(batch(1L, frontend, List.of(commit(sha('a'), "로그인 화면")), List.of()));

        SummaryInput input = builder(NO_LIMIT).build(batches, List.of(P12, P15));

        assertThat(input.text()).startsWith("""
                ## 이전에 PM에게 이미 보고한 기능 (참고용. 이번 변경 아님)
                - [P12] 11-23 09:00 🟡 로그인 서버 기능이 준비됐어요 (화면 연결 전)
                - [P15] 11-23 21:00 🟢 회원가입을 직접 써볼 수 있어요

                # 레포: team1/frontend
                """);
        assertThat(input.knownPreviousIds()).containsExactly(12L, 15L);
        assertThat(input.knownShas()).containsExactly(sha('a'));
        assertThat(input.droppedPreviousCount()).isZero();
    }

    @Test
    void 이전_항목이_없으면_섹션을_넣지_않는다() {
        List<ChangeBatch> batches = List.of(batch(1L, frontend, List.of(commit(sha('a'), "로그인 화면")), List.of()));

        SummaryInput input = builder(NO_LIMIT).build(batches, List.of());

        assertThat(input.text()).startsWith("# 레포: team1/frontend").doesNotContain("이전에 PM에게");
        assertThat(input.knownPreviousIds()).isEmpty();
        assertThat(builder(NO_LIMIT).build(batches).text()).isEqualTo(input.text());
    }

    @Test
    void 상한을_넘으면_patch와_메시지를_먼저_줄이고_이전_항목은_마지막까지_남긴다() {
        List<ChangeBatch> batches = shrinkableBatches();
        List<PreviousItem> previous = List.of(P12, P15, P18);
        int minimalChanges = builder(1).build(batches).text().length();      // patch 제거 + 메시지 첫 줄까지 줄인 길이
        int allPrevious = builder(NO_LIMIT).build(List.of(), previous).text().length();

        SummaryInput input = builder(minimalChanges + allPrevious).build(batches, previous);

        assertThat(input.droppedPatchCount()).isEqualTo(1);
        assertThat(input.messagesShortened()).isTrue();
        assertThat(input.droppedPreviousCount()).isZero();
        assertThat(input.knownPreviousIds()).containsExactly(12L, 15L, 18L);
        assertThat(input.overLimit()).isFalse();
    }

    @Test
    void 그래도_넘으면_이전_항목을_오래된_것부터_뺀다() {
        List<ChangeBatch> batches = shrinkableBatches();
        int minimalChanges = builder(1).build(batches).text().length();
        int newestTwo = builder(NO_LIMIT).build(List.of(), List.of(P15, P18)).text().length();

        SummaryInput input = builder(minimalChanges + newestTwo).build(batches, List.of(P12, P15, P18));

        assertThat(input.droppedPreviousCount()).isEqualTo(1);
        assertThat(input.knownPreviousIds()).containsExactly(15L, 18L);
        assertThat(input.text()).doesNotContain("[P12]").contains("[P15]", "[P18]");
        assertThat(input.overLimit()).isFalse();
    }

    private List<ChangeBatch> shrinkableBatches() {
        return List.of(batch(1L, backend,
                List.of(commit(sha('a'), "로그인 API 추가\n\n" + "상세 설명 ".repeat(300))),
                List.of(file("AuthController.java", "+" + "A".repeat(3000)))));
    }

    private static SummaryInputBuilder builder(int maxInputChars) {
        return new SummaryInputBuilder(properties(maxInputChars, 40, 1_000_000));
    }
}
