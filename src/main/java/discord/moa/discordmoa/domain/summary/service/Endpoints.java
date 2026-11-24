package discord.moa.discordmoa.domain.summary.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM이 준 API 주소("METHOD /path")를 같은 규칙으로 정규화한다. "post /api/auth/login/"과
 * "POST /api/auth/login"이 같은 주소로 비교되게 하는 것이 목적이다.
 * <ul>
 *   <li>method는 대문자. HTTP method가 아니거나 형식이 깨진 항목은 버린다 (요약은 실패시키지 않는다)</li>
 *   <li>쿼리스트링·# 이후 제거, 끝의 "/" 제거, 연속 "/" 하나로</li>
 *   <li>"https://host"나 "${BASE_URL}" 같은 앞부분 제거</li>
 *   <li>path 변수는 이름과 관계없이 {id}로: "{postId}", ":postId", "${postId}" → "{id}".
 *       서버와 화면이 변수 이름을 다르게 써도 같은 주소로 비교되게 한다</li>
 *   <li>중복 제거, 저장 상한(JSON 배열 문자열 500자) 안에 들어가는 만큼만</li>
 * </ul>
 */
@Slf4j
public final class Endpoints {

    public static final int MAX_STORED_LENGTH = 500;   // DigestItem.endpoints 컬럼 길이 (JSON 배열 문자열)
    static final int MAX_PATH_LENGTH = 200;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern SCHEME_AND_HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*");
    private static final Pattern LEADING_TEMPLATE = Pattern.compile("^(\\$\\{[^}]*}|\\{\\{[^}]*}})+");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\$\\{\\s*([^}]*?)\\s*}");
    private static final Pattern COLON_VARIABLE = Pattern.compile("(?<=/):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern BRACE_VARIABLE = Pattern.compile("\\{[^}/]*}");
    private static final String PATH_VARIABLE = "{id}";
    private static final Pattern SLASHES = Pattern.compile("/{2,}");

    private Endpoints() {
    }

    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            String endpoint = normalizeOne(value);
            if (endpoint == null) {
                log.warn("형식이 다른 API 주소라 버려요: '{}'", value);
                continue;
            }
            unique.add(endpoint);
        }
        return fit(new ArrayList<>(unique));
    }

    /**
     * @return 정규화한 "METHOD /path", 형식이 다르면 null
     */
    static String normalizeOne(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.strip().split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return null;
        }
        String path = parts[1].strip();
        path = cutAt(cutAt(path, '?'), '#');
        path = SCHEME_AND_HOST.matcher(path).replaceFirst("");
        path = LEADING_TEMPLATE.matcher(path).replaceFirst("");
        path = TEMPLATE_VARIABLE.matcher(path).replaceAll(PATH_VARIABLE);
        path = COLON_VARIABLE.matcher(path).replaceAll(PATH_VARIABLE);
        path = BRACE_VARIABLE.matcher(path).replaceAll(java.util.regex.Matcher.quoteReplacement(PATH_VARIABLE));
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        path = SLASHES.matcher(path).replaceAll("/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > MAX_PATH_LENGTH || path.chars().anyMatch(c -> Character.isWhitespace(c)
                || Character.isISOControl(c) || c == '"' || c == '\\' || c == '`')) {
            return null;
        }
        return method + " " + path;
    }

    private static String cutAt(String path, char marker) {
        int index = path.indexOf(marker);
        return index < 0 ? path : path.substring(0, index);
    }

    /**
     * JSON 배열 문자열(["a","b"])로 저장했을 때 상한을 넘지 않는 만큼 앞에서부터 남긴다.
     * normalizeOne이 따옴표·역슬래시·제어 문자를 거르므로 이스케이프 없이 길이를 셀 수 있다.
     */
    private static List<String> fit(List<String> endpoints) {
        List<String> kept = new ArrayList<>();
        int length = 2;   // []
        for (String endpoint : endpoints) {
            int added = endpoint.length() + 2 + (kept.isEmpty() ? 0 : 1);   // "…" + 쉼표
            if (length + added > MAX_STORED_LENGTH) {
                log.warn("API 주소가 저장 상한({}자)을 넘어 일부만 남겨요: 남김 {}개, 버림 {}개",
                        MAX_STORED_LENGTH, kept.size(), endpoints.size() - kept.size());
                break;
            }
            kept.add(endpoint);
            length += added;
        }
        return List.copyOf(kept);
    }
}
