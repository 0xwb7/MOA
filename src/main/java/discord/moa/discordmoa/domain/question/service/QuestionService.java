package discord.moa.discordmoa.domain.question.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.domain.question.entity.DigestQuestion;
import discord.moa.discordmoa.domain.question.repository.DigestQuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "개발자에게 질문하기" 제출 처리. 조회·검사(prepare)와 저장(record)을 따로 두어,
 * 그 사이의 Discord 게시는 트랜잭션 밖에서 하게 한다 (게시하는 동안 DB 커넥션을 잡지 않는다).
 */
@Service
public class QuestionService {

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = DigestQuestion.CONTENT_MAX_LENGTH;
    static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final DigestItemRepository digestItemRepository;
    private final ChangeBatchRepository changeBatchRepository;
    private final DigestQuestionRepository questionRepository;
    private final QuestionTargetResolver targetResolver;
    private final Clock clock;

    public QuestionService(DigestItemRepository digestItemRepository, ChangeBatchRepository changeBatchRepository,
                           DigestQuestionRepository questionRepository, QuestionTargetResolver targetResolver,
                           Clock clock) {
        this.digestItemRepository = digestItemRepository;
        this.changeBatchRepository = changeBatchRepository;
        this.questionRepository = questionRepository;
        this.targetResolver = targetResolver;
        this.clock = clock;
    }

    /**
     * 항목을 찾고, 연속 질문을 막고, 게시할 메시지를 만든다. 아무것도 저장하지 않는다.
     */
    @Transactional(readOnly = true)
    public QuestionPreparation prepare(long itemId, long askerId, String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.length() < MIN_LENGTH || question.length() > MAX_LENGTH) {
            return QuestionPreparation.of(QuestionPreparation.Status.INVALID_CONTENT);
        }
        Optional<DigestItem> found = digestItemRepository.findWithTeamById(itemId);
        if (found.isEmpty()) {
            return QuestionPreparation.of(QuestionPreparation.Status.NOT_FOUND);
        }
        if (questionRepository.existsByDigestItemIdAndAskerDiscordUserIdAndCreatedAtAfter(
                itemId, askerId, clock.instant().minus(COOLDOWN))) {
            return QuestionPreparation.of(QuestionPreparation.Status.TOO_SOON);
        }

        DigestItem item = found.get();
        Map<String, CommitSnapshot> commitBySha = commitsOfDigest(item.getDigest().getId());
        List<CommitSnapshot> foundCommits = new ArrayList<>();
        List<QuestionCommit> commits = new ArrayList<>();
        for (String sha : item.getCommitShas()) {
            CommitSnapshot commit = commitBySha.get(sha);
            if (commit != null) {
                foundCommits.add(commit);
            }
            commits.add(new QuestionCommit(sha, commit == null ? null : commit.htmlUrl()));
        }

        QuestionTargets targets = targetResolver.resolve(foundCommits, askerId);
        String content = QuestionMessageFormatter.format(askerId, item.getTitle(), item.getText(), question, targets, commits);
        return QuestionPreparation.ready(new QuestionPost(itemId, item.getDigest().getTeam().getName(),
                item.getDiscordMessageId(), content, targets.mentionUserIds(), question));
    }

    /**
     * 게시에 성공한 질문을 저장한다. 연속 질문 제한은 이 시각(createdAt)을 기준으로 한다.
     */
    @Transactional
    public void record(long itemId, long askerId, String question, long postedMessageId) {
        questionRepository.save(new DigestQuestion(digestItemRepository.getReferenceById(itemId), askerId, question,
                postedMessageId, clock.instant()));
    }

    // 같은 회차에 담긴 배치의 커밋만 본다 (다른 회차의 같은 SHA와 섞이지 않게)
    private Map<String, CommitSnapshot> commitsOfDigest(Long digestId) {
        Map<String, CommitSnapshot> bySha = new HashMap<>();
        for (ChangeBatch batch : changeBatchRepository.findAllByDigestIdOrderByIdAsc(digestId)) {
            for (CommitSnapshot commit : batch.getCommits()) {
                bySha.putIfAbsent(commit.sha(), commit);
            }
        }
        return bySha;
    }
}
