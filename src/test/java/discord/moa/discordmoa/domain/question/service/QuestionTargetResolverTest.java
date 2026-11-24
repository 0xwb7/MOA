package discord.moa.discordmoa.domain.question.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QuestionTargetResolverTest {

    private static final long ASKER = 7L;

    private final AccountLinkRepository accountLinkRepository = mock(AccountLinkRepository.class);
    private final QuestionTargetResolver resolver = new QuestionTargetResolver(accountLinkRepository);

    @BeforeEach
    void setUp() {
        given(accountLinkRepository.findByGithubUserId(anyLong())).willReturn(Optional.empty());
        link(101L, 1001L);   // alice
        link(102L, 1002L);   // bob
        link(107L, ASKER);   // 질문한 사람의 GitHub 계정
    }

    @Test
    void 연동된_작성자는_멘션하고_미연동_작성자는_이름으로_남긴다() {
        QuestionTargets targets = resolver.resolve(List.of(
                commit("alice", 101L, "Alice"),
                commit("carol", 103L, "Carol"),
                commit("bob", 102L, "Bob")), ASKER);

        assertThat(targets.mentionUserIds()).containsExactly(1001L, 1002L);
        assertThat(targets.unlinkedNames()).containsExactly("carol");
    }

    @Test
    void GitHub_계정이_없는_커밋은_커밋_작성자_이름을_쓴다() {
        QuestionTargets targets = resolver.resolve(List.of(commit(null, null, "Dave Kim"), commit(null, null, " ")), ASKER);

        assertThat(targets.mentionUserIds()).isEmpty();
        assertThat(targets.unlinkedNames()).containsExactly("Dave Kim");
    }

    @Test
    void 같은_작성자는_한_번만_넣고_연동_조회도_한_번만_한다() {
        QuestionTargets targets = resolver.resolve(List.of(
                commit("alice", 101L, "Alice"), commit("alice", 101L, "Alice"),
                commit("carol", 103L, "Carol"), commit("carol", 103L, "Carol"),
                commit(null, null, "Dave"), commit(null, null, "Dave")), ASKER);

        assertThat(targets.mentionUserIds()).containsExactly(1001L);
        assertThat(targets.unlinkedNames()).containsExactly("carol", "Dave");
        verify(accountLinkRepository, times(1)).findByGithubUserId(101L);
    }

    @Test
    void 질문한_사람_본인은_멘션하지_않는다() {
        QuestionTargets targets = resolver.resolve(List.of(commit("me", 107L, "Me"), commit("alice", 101L, "Alice")), ASKER);

        assertThat(targets.mentionUserIds()).containsExactly(1001L);
        assertThat(targets.unlinkedNames()).isEmpty();
    }

    @Test
    void 본인_커밋뿐이면_멘션할_사람이_없다() {
        QuestionTargets targets = resolver.resolve(List.of(commit("me", 107L, "Me")), ASKER);

        assertThat(targets.mentionUserIds()).isEmpty();
        assertThat(targets.unlinkedNames()).isEmpty();
    }

    private void link(long githubId, long discordId) {
        given(accountLinkRepository.findByGithubUserId(githubId))
                .willReturn(Optional.of(new AccountLink(discordId, githubId, "login" + githubId)));
    }

    private static CommitSnapshot commit(String login, Long githubId, String name) {
        return new CommitSnapshot("a".repeat(40), "msg", login, githubId, name, Instant.EPOCH, null);
    }
}
