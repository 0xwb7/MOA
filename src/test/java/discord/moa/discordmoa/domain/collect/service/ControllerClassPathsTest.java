package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.github.client.GitHubClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ControllerClassPathsTest {

    private static final String HEAD = "h".repeat(40);
    private static final String AUTH = "@RestController\n@RequestMapping(\"/api/auth\")\nclass AuthController {}";

    private final GitHubClient gitHubClient = mock(GitHubClient.class);
    private final ControllerClassPaths classPaths = new ControllerClassPaths(gitHubClient);

    @Test
    void 매핑이_바뀐_컨트롤러_파일만_head_시점_내용으로_클래스_경로를_채운다() {
        givenContent("src/AuthController.java", AUTH);
        FileChange controller = file("src/AuthController.java", "modified", "+    @PostMapping(\"/signup\")");
        FileChange service = file("src/AuthService.java", "modified", "+    void signup() {}");
        FileChange screen = file("web/Login.tsx", "added", "+ fetch('/api/auth/Mapping(')");

        List<FileChange> filled = classPaths.fill(1L, "team1", "backend", HEAD, List.of(controller, service, screen));

        assertThat(filled).extracting(FileChange::classPath).containsExactly("/api/auth", null, null);
        assertThat(filled.getFirst().patch()).isEqualTo(controller.patch());
        verify(gitHubClient).findFileContent(1L, "team1", "backend", "src/AuthController.java", HEAD);
        verify(gitHubClient, times(1)).findFileContent(eq(1L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 삭제된_파일과_diff가_없는_파일은_조회하지_않는다() {
        List<FileChange> files = List.of(
                file("src/OldController.java", "removed", "-    @GetMapping(\"/old\")"),
                file("src/BigController.kt", "modified", null));

        assertThat(classPaths.fill(1L, "team1", "backend", HEAD, files)).isEqualTo(files);
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void 커밋별로_여러_번_나온_같은_파일은_한_번만_조회하고_모두_채운다() {
        givenContent("src/AuthController.kt", AUTH);
        FileChange first = new FileChange("c1", "src/AuthController.kt", "modified", 3, 0, "+ @PostMapping(\"/a\")", false);
        FileChange second = new FileChange("c2", "src/AuthController.kt", "modified", 3, 0, "+ @GetMapping(\"/b\")", false);

        List<FileChange> filled = classPaths.fill(1L, "team1", "backend", HEAD, List.of(first, second));

        assertThat(filled).extracting(FileChange::classPath).containsExactly("/api/auth", "/api/auth");
        verify(gitHubClient, times(1)).findFileContent(1L, "team1", "backend", "src/AuthController.kt", HEAD);
    }

    @Test
    void 배치당_10개_파일까지만_조회한다() {
        List<FileChange> files = IntStream.range(0, 12)
                .mapToObj(i -> file("src/C" + i + "Controller.java", "modified", "+ @GetMapping(\"/x\")"))
                .toList();
        given(gitHubClient.findFileContent(eq(1L), anyString(), anyString(), anyString(), anyString()))
                .willReturn(Optional.of(AUTH));

        List<FileChange> filled = classPaths.fill(1L, "team1", "backend", HEAD, files);

        assertThat(filled.stream().filter(file -> file.classPath() != null)).hasSize(ControllerClassPaths.MAX_FILES_PER_BATCH);
        assertThat(filled.get(10).classPath()).isNull();
        verify(gitHubClient, never()).findFileContent(1L, "team1", "backend", "src/C10Controller.java", HEAD);
    }

    @Test
    void 조회가_실패하거나_404면_그_파일만_경로_없이_두고_나머지는_계속한다() {
        given(gitHubClient.findFileContent(1L, "team1", "backend", "src/AController.java", HEAD))
                .willThrow(new ResourceAccessException("timeout"));
        given(gitHubClient.findFileContent(1L, "team1", "backend", "src/BController.java", HEAD))
                .willReturn(Optional.empty());
        givenContent("src/CController.java", AUTH);

        List<FileChange> filled = classPaths.fill(1L, "team1", "backend", HEAD, List.of(
                file("src/AController.java", "modified", "+ @GetMapping(\"/a\")"),
                file("src/BController.java", "modified", "+ @GetMapping(\"/b\")"),
                file("src/CController.java", "modified", "+ @GetMapping(\"/c\")")));

        assertThat(filled).extracting(FileChange::classPath).containsExactly(null, null, "/api/auth");
    }

    private void givenContent(String filename, String content) {
        given(gitHubClient.findFileContent(1L, "team1", "backend", filename, HEAD)).willReturn(Optional.of(content));
    }

    private static FileChange file(String filename, String status, String patch) {
        return new FileChange(null, filename, status, 3, 0, patch, false);
    }
}
