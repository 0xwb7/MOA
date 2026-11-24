package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.ChangeBatchStatus;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * 레포 비활성화와 그 레포의 미발송 배치 정리를 한 트랜잭션에서 한다.
 * /레포해제, 수집 중 자동 비활성화(GitHub에서 삭제·재생성), 같은 이름 재등록 정리가 함께 쓴다.
 * 이미 회차에 담긴 배치(PREPARED)와 보낸 배치(SENT)는 건드리지 않는다.
 */
@Component
public class TrackedRepoDeactivator {

    private final TrackedRepoRepository trackedRepoRepository;
    private final ChangeBatchRepository changeBatchRepository;

    public TrackedRepoDeactivator(TrackedRepoRepository trackedRepoRepository,
                                  ChangeBatchRepository changeBatchRepository) {
        this.trackedRepoRepository = trackedRepoRepository;
        this.changeBatchRepository = changeBatchRepository;
    }

    /**
     * @return 이번에 비활성화했으면 보내지 않게 된 커밋 수, 이미 비활성이었으면 빈 값
     */
    @Transactional
    public OptionalInt deactivate(Long repoId, Instant now) {
        if (trackedRepoRepository.deactivate(repoId, now) == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(discardCollected(repoId));
    }

    /**
     * 레포의 COLLECTED 배치를 DISCARDED로 바꾼다.
     *
     * @return 실제로 버린 배치의 커밋 수
     */
    @Transactional
    public int discardCollected(Long repoId) {
        List<Long> ids = changeBatchRepository.findAllByTrackedRepoIdAndStatus(repoId, ChangeBatchStatus.COLLECTED).stream()
                .map(ChangeBatch::getId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        changeBatchRepository.changeStatusByIds(ids, ChangeBatchStatus.COLLECTED, ChangeBatchStatus.DISCARDED);
        // 조회와 변경 사이에 회차에 담긴 배치는 세지 않는다
        return changeBatchRepository.findAllById(ids).stream()
                .filter(batch -> batch.getStatus() == ChangeBatchStatus.DISCARDED)
                .mapToInt(batch -> batch.getCommits().size())
                .sum();
    }
}
