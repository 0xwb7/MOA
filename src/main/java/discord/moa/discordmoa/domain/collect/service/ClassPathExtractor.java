package discord.moa.discordmoa.domain.collect.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring 컨트롤러 소스(Java·Kotlin)에서 클래스 레벨 @RequestMapping 경로를 뽑는다.
 * <ul>
 *   <li>첫 class(interface·object) 선언 앞에 붙은 @RequestMapping만 본다. 주석 안의 것은 무시한다.</li>
 *   <li>("/api"), (value = "/api"), (path = "/api"), ({"/a", "/b"}), (["/a"]), (value = ["/a"]) → 첫 값</li>
 *   <li>상수(ApiPaths.AUTH), 프로퍼티 자리표시자(${...}), 경로 없이 다른 속성만 있으면 null</li>
 * </ul>
 */
public final class ClassPathExtractor {

    private static final Pattern CLASS_DECLARATION =
            Pattern.compile("\\b(?:class|interface|object)\\s+[A-Za-z_$]");
    private static final Pattern REQUEST_MAPPING = Pattern.compile("@(?:[\\w.]+\\.)?RequestMapping\\s*\\(");
    private static final Pattern NAMED_PATH = Pattern.compile("\\b(?:value|path)\\s*=\\s*");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private ClassPathExtractor() {
    }

    /**
     * @return "/api/auth" 형태 (앞에 "/", 끝의 "/" 없음). 없으면 null
     */
    public static String extract(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String code = stripComments(source);
        Matcher declaration = CLASS_DECLARATION.matcher(code);
        if (!declaration.find()) {
            return null;
        }
        String beforeClass = code.substring(0, declaration.start());

        Matcher mapping = REQUEST_MAPPING.matcher(beforeClass);
        int argsStart = -1;
        while (mapping.find()) {
            argsStart = mapping.end();   // 클래스 바로 앞의(마지막) @RequestMapping
        }
        if (argsStart < 0) {
            return null;
        }
        String args = arguments(beforeClass, argsStart);
        if (args == null) {
            return null;
        }
        return normalize(firstPath(args.strip()));
    }

    private static String firstPath(String args) {
        if (args.startsWith("\"") || args.startsWith("{") || args.startsWith("[")) {
            return firstLiteral(args);   // 위치 인자 = value
        }
        Matcher named = NAMED_PATH.matcher(args);
        if (!named.find()) {
            return null;   // produces 등 경로가 아닌 속성만 있음
        }
        String rest = args.substring(named.end()).strip();
        if (!(rest.startsWith("\"") || rest.startsWith("{") || rest.startsWith("["))) {
            return null;   // 상수 등
        }
        return firstLiteral(rest);
    }

    private static String firstLiteral(String text) {
        String head = text.startsWith("{") || text.startsWith("[") ? text.substring(1).strip() : text;
        if (!head.startsWith("\"")) {
            return null;   // 배열 첫 값이 상수
        }
        Matcher literal = STRING_LITERAL.matcher(head);
        return literal.lookingAt() ? literal.group(1) : null;
    }

    private static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String value = path.strip();
        if (value.isEmpty() || value.contains("${")) {
            return null;
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.equals("/") ? null : value;
    }

    /**
     * 여는 괄호 바로 뒤(start)부터 짝이 맞는 닫는 괄호 앞까지. 문자열 안의 괄호는 세지 않는다.
     */
    private static String arguments(String text, int start) {
        int depth = 1;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(start, i);
            }
        }
        return null;
    }

    /**
     * 문자열 리터럴은 그대로 두고 // 주석과 블록 주석을 지운다.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '"') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                out.append(source, i, end);
                i = end;
            } else if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
