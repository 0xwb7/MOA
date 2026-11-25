package discord.moa.discordmoa.discord.listener;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AdminPermissionTest {

    @Test
    void 서버_관리_권한이_있어야_운영진이다() {
        Member admin = mock(Member.class);
        given(admin.hasPermission(Permission.MANAGE_SERVER)).willReturn(true);
        Member developer = mock(Member.class);

        assertThat(SlashCommandListener.isAdmin(admin)).isTrue();
        assertThat(SlashCommandListener.isAdmin(developer)).isFalse();
        assertThat(SlashCommandListener.isAdmin(null)).isFalse();   // DM 등 서버 밖
    }
}
