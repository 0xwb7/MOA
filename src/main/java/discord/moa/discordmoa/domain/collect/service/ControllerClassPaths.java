package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.github.client.GitHubClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 컨트롤러 파일에 클래스 레벨 @RequestMapping 경로를 채운다.
 * 기존 컨트롤러에 메서드만 추가하면 diff에 클래스 경로가 보이지 않아, LLM이 서버 주소를 "/signup"처럼 앞부분 없이 보게 된다.
 * 그래서 head 시점의 파일 전체를 받아 경로만 뽑는다. 파일 내용은 저장하지도 LLM에 보내지도 않는다.
 * <p>
 * 대상: .java/.kt, 삭제되지 않음, patch에 "Mapping("이 있음. 파일 이름 기준 중복 없이 배치당 최대 10개.
 * 조회가 실패하면 그 파일만 경로 없이 둔다 (수집 전체를 실패시키지 않는다).
 */
@Slf4j
@Component
public class ControllerClassPaths {

    static final int MAX_FILES_PER_BATCH = 10;

    private final GitHubClient gitHubClient;

    public ControllerClassPaths(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    /**
     * @param ref 배치의 head 커밋 SHA
     */
    public List<FileChange> fill(Long installationId, String owner, String repo, String ref, List<FileChange> files) {
        Set<String> candidates = new LinkedHashSet<>();
        files.stream().filter(ControllerClassPaths::isCandidate).forEach(file -> candidates.add(file.filename()));
        if (candidates.isEmpty()) {
            return files;
        }
        if (candidates.size() > MAX_FILES_PER_BATCH) {
            log.info("컨트롤러 파일이 많아 앞의 {}개만 클래스 경로를 확인해요: repo={}/{}, 대상 {}개",
                    MAX_FILES_PER_BATCH, owner, repo, candidates.size());
        }

        Map<String, String> classPathByFile = new HashMap<>();
        candidates.stream().limit(MAX_FILES_PER_BATCH).forEach(filename -> {
            try {
                gitHubClient.findFileContent(installationId, owner, repo, filename, ref)
                        .map(ClassPathExtractor::extract)
                        .ifPresent(classPath -> classPathByFile.put(filename, classPath));
            } catch (RuntimeException e) {
                log.warn("클래스 경로를 확인하지 못해 경로 없이 진행해요: repo={}/{}, file={}, error={}",
                        owner, repo, filename, e.toString());
            }
        });
        if (classPathByFile.isEmpty()) {
            return files;
        }
        return files.stream()
                .map(file -> classPathByFile.containsKey(file.filename())
                        ? file.withClassPath(classPathByFile.get(file.filename()))
                        : file)
                .toList();
    }

    static boolean isCandidate(FileChange file) {
        if (file.filename() == null || file.patch() == null || "removed".equals(file.status())) {
            return false;
        }
        String name = file.filename().toLowerCase(Locale.ROOT);
        return (name.endsWith(".java") || name.endsWith(".kt")) && file.patch().contains("Mapping(");
    }
}
