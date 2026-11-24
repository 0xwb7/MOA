package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.global.config.SummaryProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 팀의 COLLECTED 배치들을 LLM 입력 텍스트 하나로 만든다.
 * <ul>
 *     <li>비밀 파일(excluded-files)은 이름도 patch도 넣지 않는다.</li>
 *     <li>lock·빌드 산출물(patch-omitted-files)과 patch가 없는 파일은 이름과 줄 수만 넣는다.</li>
 *     <li>상한을 넘으면 큰 patch부터 빼고, 그래도 넘으면 커밋 메시지를 첫 줄만 남긴다.</li>
 * </ul>
 */
@Component
public class SummaryInputBuilder {

    private static final String OMITTED_GENERATED = "[diff 생략: 자동 생성 파일]";
    private static final String OMITTED_NO_PATCH = "[diff 없음]";
    private static final String OMITTED_SIZE = "[diff 생략: 입력 크기 제한]";
    static final String PREVIOUS_HEADER = "## 이전에 PM에게 이미 보고한 기능 (참고용. 이번 변경 아님)\n";
    static final String PREVIOUS_ENDPOINTS = " | 주소: ";
    static final String CLASS_PATH = " | 클래스 경로: ";

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final int maxInputChars;
    private final DateTimeFormatter previousTime;
    private final List<String> excludedPatterns;
    private final List<String> patchOmittedPatterns;

    public SummaryInputBuilder(SummaryProperties properties) {
        this.maxInputChars = properties.maxInputChars();
        this.previousTime = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(properties.zone());
        this.excludedPatterns = lowerCase(properties.excludedFiles());
        this.patchOmittedPatterns = lowerCase(properties.patchOmittedFiles());
    }

    public SummaryInput build(List<ChangeBatch> batches) {
        return build(batches, List.of());
    }

    /**
     * @param previousItems 이전 회차에 PM에게 보고한 항목 (오래된 순). 비어 있으면 섹션을 넣지 않는다
     */
    public SummaryInput build(List<ChangeBatch> batches, List<PreviousItem> previousItems) {
        List<RepoBlock> repos = toBlocks(batches);
        Set<String> knownShas = new LinkedHashSet<>();
        repos.forEach(repo -> repo.commits.forEach(commit -> knownShas.add(commit.sha)));
        List<PreviousItem> previous = new ArrayList<>(previousItems);

        int length = renderPrevious(previous).length() + render(repos).length();

        int droppedPatchCount = 0;
        if (length > maxInputChars) {
            List<FileBlock> withPatch = repos.stream()
                    .flatMap(repo -> repo.files.stream())
                    .filter(file -> file.patch != null)
                    .sorted(Comparator.comparingInt((FileBlock file) -> file.patch.length()).reversed())
                    .toList();
            for (FileBlock file : withPatch) {
                if (length <= maxInputChars) {
                    break;
                }
                int before = file.render().length();
                file.patch = null;
                file.note = OMITTED_SIZE;
                length -= before - file.render().length();
                droppedPatchCount++;
            }
        }

        boolean messagesShortened = false;
        if (length > maxInputChars) {
            repos.forEach(repo -> repo.commits.forEach(CommitBlock::shorten));
            messagesShortened = true;
        }

        // 이전 항목은 서버·화면이 다른 회차에 나뉜 기능을 판단하는 핵심 근거라 가장 마지막에, 오래된 것부터 뺀다
        String changes = render(repos);
        int droppedPreviousCount = 0;
        while (!previous.isEmpty() && renderPrevious(previous).length() + changes.length() > maxInputChars) {
            previous.removeFirst();
            droppedPreviousCount++;
        }

        String text = renderPrevious(previous) + changes;
        Set<Long> knownPreviousIds = new LinkedHashSet<>();
        previous.forEach(item -> knownPreviousIds.add(item.id()));
        return new SummaryInput(text, knownShas, knownPreviousIds, droppedPatchCount, messagesShortened,
                droppedPreviousCount, text.length() > maxInputChars);
    }

    private String renderPrevious(List<PreviousItem> previous) {
        if (previous.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(PREVIOUS_HEADER);
        for (PreviousItem item : previous) {
            text.append("- [P").append(item.id()).append("] ")
                    .append(previousTime.format(item.scheduledFor())).append(' ')
                    .append(item.status().emoji()).append(' ')
                    .append(item.title());
            // 주소는 제목과 따로 붙여서 제목이 잘려도 남는다. 주소가 없으면 기존 형식 그대로
            if (!item.endpoints().isEmpty()) {
                text.append(PREVIOUS_ENDPOINTS).append(String.join(", ", item.endpoints()));
            }
            text.append('\n');
        }
        return text.append('\n').toString();
    }

    boolean isExcluded(String filename) {
        return matchesAny(excludedPatterns, filename);
    }

    boolean isPatchOmitted(String filename) {
        return matchesAny(patchOmittedPatterns, filename);
    }

    private List<RepoBlock> toBlocks(List<ChangeBatch> batches) {
        Map<Long, RepoBlock> byRepo = new LinkedHashMap<>();
        for (ChangeBatch batch : batches) {
            RepoBlock repo = byRepo.computeIfAbsent(batch.getTrackedRepo().getId(),
                    id -> new RepoBlock(batch.getTrackedRepo().getFullName()));
            repo.partial |= batch.isForcePushDetected() || batch.isTruncated();
            for (CommitSnapshot commit : batch.getCommits()) {
                repo.commits.add(new CommitBlock(commit.sha(), commit.message()));
            }
            for (FileChange file : batch.getFiles()) {
                if (isExcluded(file.filename())) {
                    continue;
                }
                repo.files.add(toFileBlock(file));
            }
        }
        return new ArrayList<>(byRepo.values());
    }

    private FileBlock toFileBlock(FileChange file) {
        FileBlock block = new FileBlock(file);
        if (isPatchOmitted(file.filename())) {
            block.note = OMITTED_GENERATED;
        } else if (file.patch() == null) {
            block.note = OMITTED_NO_PATCH;
        } else {
            block.patch = file.patch();
            if (file.patchTruncated()) {
                block.note = "[diff 일부만 포함]";
            }
        }
        return block;
    }

    private static String render(List<RepoBlock> repos) {
        StringBuilder text = new StringBuilder();
        for (RepoBlock repo : repos) {
            text.append(repo.renderHeader());
            repo.commits.forEach(commit -> text.append(commit.render()));
            text.append("\n## 변경 파일\n");
            if (repo.files.isEmpty()) {
                text.append("(표시할 파일 없음)\n");
            }
            repo.files.forEach(file -> text.append(file.render()));
            text.append('\n');
        }
        return text.toString();
    }

    private boolean matchesAny(List<String> patterns, String filename) {
        if (filename == null) {
            return false;
        }
        String path = filename.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(pattern -> matcher.match(pattern, path));
    }

    private static List<String> lowerCase(List<String> patterns) {
        return patterns == null ? List.of() : patterns.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    private static final class RepoBlock {
        private final String fullName;
        private final List<CommitBlock> commits = new ArrayList<>();
        private final List<FileBlock> files = new ArrayList<>();
        private boolean partial;

        private RepoBlock(String fullName) {
            this.fullName = fullName;
        }

        private String renderHeader() {
            return "# 레포: " + fullName + "\n"
                    + (partial ? "(히스토리 변경이나 개수 제한으로 일부 커밋만 포함됨)\n" : "")
                    + "\n## 커밋\n";
        }
    }

    private static final class CommitBlock {
        private final String sha;
        private String message;

        private CommitBlock(String sha, String message) {
            this.sha = sha;
            this.message = message == null || message.isBlank() ? "(메시지 없음)" : message.strip();
        }

        private void shorten() {
            message = message.lines().findFirst().orElse("").strip();
        }

        private String render() {
            StringBuilder text = new StringBuilder("- SHA: ").append(sha).append("\n  메시지:\n");
            message.lines().forEach(line -> text.append("    ").append(line).append('\n'));
            return text.toString();
        }
    }

    private static final class FileBlock {
        private final FileChange file;
        private String patch;
        private String note;

        private FileBlock(FileChange file) {
            this.file = file;
        }

        private String render() {
            StringBuilder text = new StringBuilder("- ").append(file.filename())
                    .append(" (").append(file.status())
                    .append(", +").append(file.additions()).append(" -").append(file.deletions());
            if (file.commitSha() != null) {
                text.append(", 커밋 ").append(file.commitSha());
            }
            text.append(')');
            // 메서드만 추가된 컨트롤러 diff에는 클래스 경로가 안 보여서 따로 알려 준다 (수집 때 head 시점 파일에서 뽑음)
            if (file.classPath() != null) {
                text.append(CLASS_PATH).append(file.classPath());
            }
            if (note != null) {
                text.append(' ').append(note);
            }
            text.append('\n');
            if (patch != null) {
                text.append("<diff>\n").append(patch).append("\n</diff>\n");
            }
            return text.toString();
        }
    }
}
