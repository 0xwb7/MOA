package discord.moa.discordmoa.discord.command;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandRegistrarTest {

    private static final long GUILD_ID = 123456789012345678L;

    private final JDA jda = mock(JDA.class);

    @Test
    void 봇이_서버에_없으면_원인이_드러나는_메시지로_실패한다() {
        when(jda.getGuildById(GUILD_ID)).thenReturn(null);

        assertThatThrownBy(() -> new CommandRegistrar(jda, String.valueOf(GUILD_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("봇이 서버(ID: " + GUILD_ID + ")에 초대되지 않았어요. 초대 후 다시 시작해 주세요");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "abc", "123-456", "99999999999999999999"})
    void 서버_ID가_비었거나_숫자가_아니면_설정_문제로_실패한다(String guildId) {
        assertThatThrownBy(() -> CommandRegistrar.requireGuild(jda, guildId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("DISCORD_GUILD_ID가 올바른 서버 ID가 아니에요");
        verify(jda, never()).getGuildById(any(Long.class));
    }

    @Test
    void 서버가_있으면_그_서버에_명령어를_등록한다() {
        Guild guild = mock(Guild.class);
        CommandListUpdateAction update = mock(CommandListUpdateAction.class, RETURNS_SELF);
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
        when(guild.updateCommands()).thenReturn(update);

        new CommandRegistrar(jda, " " + GUILD_ID + " ");

        verify(update).queue();
        assertThat(CommandRegistrar.requireGuild(jda, String.valueOf(GUILD_ID))).isSameAs(guild);
    }

    @Test
    void 사용법은_권한_제한_없이_누구나_쓸_수_있게_등록한다() {
        assertThat(registeredCommand("사용법").getDefaultPermissions())
                .isEqualTo(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions.ENABLED);
    }

    @Test
    void 레포해제는_운영진_전용으로_등록한다() {
        assertThat(registeredCommand("레포해제").getDefaultPermissions())
                .isEqualTo(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions.enabledFor(
                        net.dv8tion.jda.api.Permission.MANAGE_SERVER));
    }

    private net.dv8tion.jda.api.interactions.commands.build.CommandData registeredCommand(String name) {
        Guild guild = mock(Guild.class);
        CommandListUpdateAction update = mock(CommandListUpdateAction.class, RETURNS_SELF);
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
        when(guild.updateCommands()).thenReturn(update);

        new CommandRegistrar(jda, String.valueOf(GUILD_ID));

        // addCommands(CommandData...)에 넘긴 명령어 전체 (가변 인자를 펼쳐서 본다)
        return org.mockito.Mockito.mockingDetails(update).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("addCommands"))
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .map(net.dv8tion.jda.api.interactions.commands.build.CommandData.class::cast)
                .filter(command -> command.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
