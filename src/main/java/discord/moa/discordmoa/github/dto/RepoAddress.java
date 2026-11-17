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

    public String fullName() {
        return owner + "/" + repo;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("GitHub 레포 주소 형식이 아니에요. 예: https://github.com/owner/repo");
    }
}
