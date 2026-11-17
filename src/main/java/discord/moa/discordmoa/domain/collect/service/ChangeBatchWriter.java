package discord.moa.discordmoa.domain.collect.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.repository.ChangeBatchRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangeBatchWriter {

    private final ChangeBatchRepository changeBatchRepository;
    private final TrackedRepoRepository trackedRepoRepository;

    @Transactional
    public ChangeBatch saveAndAdvance(ChangeBatch batch) {
        ChangeBatch saved = changeBatchRepository.save(batch);

        int updated = trackedRepoRepository.advanceCursor(
                batch.getTrackedRepo().getId(), batch.getBaseSha(), batch.getHeadSha());
        if (updated == 0) {
            throw new IllegalStateException(
                    "다른 수집이 먼저 진행돼 커서가 바뀌었어요: " + batch.getTrackedRepo().getFullName());
        }
        return saved;
    }
}
