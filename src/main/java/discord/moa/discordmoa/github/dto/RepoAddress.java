package discord.moa.discordmoa.github.dto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RepoAddress(String owner, String repo) {

    private static final Pattern PATTERN = Pattern.compile(
            "^https?://github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$",
            Pattern.CASE_INSENSITIVE
    );

    public static RepoAddress parse(String url) {
        if (url == null) {
            throw invalid();
        }

        Matcher matcher = PATTERN.matcher(url.strip());
        if (!matcher.matches() || matcher.group(2).matches("\\.+")) {
            throw invalid();
        }
        return new RepoAddress(matcher.group(1), matcher.group(2));
    }

    private static final Pattern FULL_NAME = Pattern.compile("^([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)$");

    /**
     * /레포해제처럼 이미 등록된 레포를 가리킬 때: "owner/repo" 또는 GitHub 주소를 받는다.
     */
    public static RepoAddress parseFullNameOrUrl(String input) {
        if (input != null) {
            Matcher matcher = FULL_NAME.matcher(input.strip());
            if (matcher.matches() && !matcher.group(2).matches("\\.+")) {
                return new RepoAddress(matcher.group(1), matcher.group(2));
            }
        }
        try {
            return parse(input);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("레포 이름(owner/repo)이나 GitHub 주소를 입력해 주세요. 예: owner/repo");
        }
    }

    public String fullName() {
        return owner + "/" + repo;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("GitHub 레포 주소 형식이 아니에요. 예: https://github.com/owner/repo");
    }
}
