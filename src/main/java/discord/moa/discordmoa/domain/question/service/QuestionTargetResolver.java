package discord.moa.discordmoa.domain.question.service;

import discord.moa.discordmoa.domain.account.entity.AccountLink;
import discord.moa.discordmoa.domain.account.repository.AccountLinkRepository;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 항목의 커밋 작성자 중 질문을 받을 개발자를 찾는다. GitHub 계정 ID → 연동된 Discord 계정.
 */
@Component
public class QuestionTargetResolver {

    private final AccountLinkRepository accountLinkRepository;

    public QuestionTargetResolver(AccountLinkRepository accountLinkRepository) {
        this.accountLinkRepository = accountLinkRepository;
    }

    /**
     * @param commits 커밋 순서대로. 같은 작성자가 여러 번 나와도 한 번만 넣는다
     * @param askerId 질문한 사람. 연동된 작성자 중 본인은 뺀다
     */
    public QuestionTargets resolve(List<CommitSnapshot> commits, long askerId) {
        Set<Long> mentions = new LinkedHashSet<>();
        Set<String> unlinked = new LinkedHashSet<>();
        Map<Long, Optional<Long>> discordIdByGithubId = new HashMap<>();

        for (CommitSnapshot commit : commits) {
            Long githubId = commit.authorGithubId();
            Optional<Long> discordId = githubId == null ? Optional.empty()
                    : discordIdByGithubId.computeIfAbsent(githubId, id ->
                    accountLinkRepository.findByGithubUserId(id).map(AccountLink::getDiscordUserId));
            if (discordId.isPresent()) {
                if (discordId.get() != askerId) {
                    mentions.add(discordId.get());
                }
                continue;
            }
            String name = displayName(commit);
            if (name != null) {
                unlinked.add(name);
            }
        }
        return new QuestionTargets(List.copyOf(mentions), List.copyOf(unlinked));
    }

    private static String displayName(CommitSnapshot commit) {
        if (commit.authorLogin() != null && !commit.authorLogin().isBlank()) {
            return commit.authorLogin().strip();
        }
        if (commit.authorName() != null && !commit.authorName().isBlank()) {
            return commit.authorName().strip();
        }
        return null;
    }
}
