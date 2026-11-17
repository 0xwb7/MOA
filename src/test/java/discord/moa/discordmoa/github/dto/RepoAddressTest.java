package discord.moa.discordmoa.github.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/moa-team/hackathon-app",
            "https://github.com/moa-team/hackathon-app/",
            "https://github.com/moa-team/hackathon-app.git",
            "https://github.com/moa-team/hackathon-app.git/",
            "http://github.com/moa-team/hackathon-app",
            "  https://github.com/moa-team/hackathon-app  "
    })
    void 허용되는_주소는_owner와_repo로_파싱된다(String url) {
        RepoAddress address = RepoAddress.parse(url);

        assertThat(address.owner()).isEqualTo("moa-team");
        assertThat(address.repo()).isEqualTo("hackathon-app");
        assertThat(address.fullName()).isEqualTo("moa-team/hackathon-app");
    }

    @Test
    void 레포_이름의_점은_유지하고_끝의_git만_제거한다() {
        RepoAddress address = RepoAddress.parse("https://github.com/owner/my.site.git");

        assertThat(address.repo()).isEqualTo("my.site");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://gitlab.com/owner/repo",          // 다른 도메인
            "https://github.com.evil.com/owner/repo", // 도메인 위장
            "https://github.com/owner",               // owner만
            "https://github.com/owner/",
            "https://github.com/owner/repo/tree/main", // 레포 하위 경로
            "github.com/owner/repo",                  // 스킴 없음
            "https://github.com/owner/..",
            "owner/repo"
    })
    void 형식이_아닌_주소는_거절한다(String url) {
        assertThatThrownBy(() -> RepoAddress.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://github.com/owner/repo");
    }
}
