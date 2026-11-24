package discord.moa.discordmoa.domain.question.service;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 팀 채널에 올릴 질문 메시지 본문. Discord 메시지 한도(2000자)를 넘으면 질문을 먼저 자른다.
 * <pre>
 * ❓ &lt;@질문자&gt;님의 질문
 * &gt; 항목: 로그인 기능이 완성됐어요
 * 로그인에 실패하면 어떤 화면이 나와요?
 * → &lt;@개발자1&gt; &lt;@개발자2&gt; · bob (디스코드 미연동)
 * 관련 커밋: [abc1234](url) [def5678](url) 외 2개
 * </pre>
 * 멘션이 실제로 알림을 보낼지는 여기서 정하지 않는다. 게시할 때 AllowedMentions로 개발자 ID만 허용한다.
 */
public final class QuestionMessageFormatter {

    public static final int MAX_LENGTH = 2000;   // Discord 메시지 본문 한도
    static final int ITEM_MAX_LENGTH = 100;
    static final int MAX_COMMITS = 5;
    static final int SHORT_SHA = 7;
    static final String NO_TARGET = "팀 개발자분들 확인 부탁드려요";
    static final String UNLINKED_SUFFIX = " (디스코드 미연동)";

    private QuestionMessageFormatter() {
    }

    /**
     * @param title 항목 제목(LLM 항목). 없으면 text의 첫 줄을 쓴다
     * @param text  항목 메시지 텍스트 (이미 마크다운 이스케이프됨)
     */
    public static String format(long askerId, String title, String text, String question,
                                QuestionTargets targets, List<QuestionCommit> commits) {
        String head = "❓ <@" + askerId + ">님의 질문\n> 항목: " + itemLabel(title, text) + "\n";
        StringBuilder tail = new StringBuilder("\n→ ").append(targetLine(targets));
        String commitLine = commitLine(commits);
        if (!commitLine.isEmpty()) {
            tail.append('\n').append(commitLine);
        }

        String escapedQuestion = MarkdownSanitizer.escape(question.strip());
        String content = head + cut(escapedQuestion, MAX_LENGTH - head.length() - tail.length()) + tail;
        // 개발자·커밋 줄만으로 한도를 넘는 극단적인 경우의 안전장치
        return content.length() <= MAX_LENGTH ? content : cut(content, MAX_LENGTH);
    }

    /**
     * 항목 이름 한 줄. 제목은 이스케이프하고, 제목이 없으면 이미 이스케이프된 text의 첫 줄을 그대로 쓴다.
     */
    static String itemLabel(String title, String text) {
        String label;
        if (title != null && !title.isBlank()) {
            label = MarkdownSanitizer.escape(oneLine(title));
        } else {
            label = text == null ? "" : text.strip().lines().findFirst().orElse("").strip();
        }
        if (label.isEmpty()) {
            label = "(제목 없음)";
        }
        return cut(label, ITEM_MAX_LENGTH);
    }

    static String targetLine(QuestionTargets targets) {
        List<String> parts = new ArrayList<>();
        if (targets.mentionUserIds().isEmpty()) {
            parts.add(NO_TARGET);
        } else {
            parts.add(String.join(" ", targets.mentionUserIds().stream().map(id -> "<@" + id + ">").toList()));
        }
        if (!targets.unlinkedNames().isEmpty()) {
            parts.add(String.join(", ", targets.unlinkedNames().stream()
                    .map(name -> MarkdownSanitizer.escape(oneLine(name)) + UNLINKED_SUFFIX).toList()));
        }
        return String.join(" · ", parts);
    }

    static String commitLine(List<QuestionCommit> commits) {
        if (commits.isEmpty()) {
            return "";
        }
        List<String> shown = commits.stream().limit(MAX_COMMITS).map(QuestionMessageFormatter::commitLink).toList();
        String line = "관련 커밋: " + String.join(" ", shown);
        if (commits.size() > MAX_COMMITS) {
            line += " 외 " + (commits.size() - MAX_COMMITS) + "개";
        }
        return line;
    }

    private static String commitLink(QuestionCommit commit) {
        String shortSha = commit.sha().substring(0, Math.min(SHORT_SHA, commit.sha().length()));
        return commit.htmlUrl() == null ? "`" + shortSha + "`" : "[" + shortSha + "](" + commit.htmlUrl() + ")";
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /**
     * available자 이내로 자르고 "…"를 붙인다. 서로게이트 쌍과 이스케이프(\)가 반으로 갈리지 않게 한다.
     */
    static String cut(String value, int available) {
        if (value.length() <= available) {
            return value;
        }
        if (available <= 1) {
            return available == 1 ? "…" : "";
        }
        int end = available - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        // 끝에 남은 백슬래시가 홀수 개면 마지막 하나가 "…"를 이스케이프하게 되므로 뺀다
        int backslashes = 0;
        while (end - backslashes > 0 && value.charAt(end - backslashes - 1) == '\\') {
            backslashes++;
        }
        if (backslashes % 2 == 1) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}
