package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * 발송 항목의 "확인했어요" 처리. 외부 API를 부르지 않아 Discord 이벤트 스레드에서 바로 호출해도 된다.
 */
@Service
@RequiredArgsConstructor
public class DigestConfirmService {

    private final DigestItemRepository digestItemRepository;
    private final Clock clock;

    /**
     * 기록하지 않고 확인 가능 여부만 본다. 응답 방식(본인에게만 보이는 답 vs 메시지 수정)을 정하기 위해 먼저 호출한다.
     */
    @Transactional(readOnly = true)
    public ConfirmCheck check(Long itemId, Long userId) {
        Optional<DigestItem> found = digestItemRepository.findWithTeamById(itemId);
        if (found.isEmpty()) {
            return ConfirmCheck.of(ConfirmCheck.Status.NOT_FOUND);
        }
        DigestItem item = found.get();
        if (!item.getDigest().getTeam().getPmDiscordUserId().equals(userId)) {
            return ConfirmCheck.of(ConfirmCheck.Status.NOT_PM);
        }
        if (item.getConfirmedAt() != null) {
            return new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, item.getConfirmedAt());
        }
        return ConfirmCheck.of(ConfirmCheck.Status.CONFIRMABLE);
    }

    /**
     * 조건부 UPDATE(confirmedAt이 비어 있을 때만)로 기록한다.
     *
     * @return 이번에 기록했으면 CONFIRMED, 그사이 다른 클릭이 먼저 기록했으면 ALREADY_CONFIRMED
     */
    public ConfirmCheck confirm(Long itemId, Long userId) {
        var now = clock.instant();
        if (digestItemRepository.confirm(itemId, userId, now) == 1) {
            return new ConfirmCheck(ConfirmCheck.Status.CONFIRMED, now);
        }
        return digestItemRepository.findById(itemId)
                .map(item -> new ConfirmCheck(ConfirmCheck.Status.ALREADY_CONFIRMED, item.getConfirmedAt()))
                .orElse(ConfirmCheck.of(ConfirmCheck.Status.NOT_FOUND));
    }
}
