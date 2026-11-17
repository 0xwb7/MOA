package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private final DigestWriter digestWriter;
    private final DigestMessagePlanner planner;
    private final DigestSender digestSender;
    private final TeamRepository teamRepository;

    public DigestSendResult sendTeam(Long teamId) {
        PreparedDigest prepared = digestWriter.prepare(teamId, Instant.now());
        List<DigestMessage> messages = toMessages(prepared);

        List<Long> messageIds;
        try {
            messageIds = digestSender.send(prepared.channelId(), messages);
        } catch (DigestSendException e) {
            return fail(prepared, messages, e.getSentMessageIds(), e.getMessage(), e);
        } catch (RuntimeException e) {
            return fail(prepared, messages, List.of(), "예상하지 못한 오류: " + e.getMessage(), e);
        }

        try {
            digestWriter.markSent(prepared, messageIdByItemId(messages, messageIds), Instant.now());
        } catch (RuntimeException e) {
            log.error("발송은 됐지만 결과 기록에 실패했어요: team={}, digestId={}, messageIds={}",
                    prepared.teamName(), prepared.digestId(), messageIds, e);
            throw e;
        }
        return new DigestSendResult(prepared.teamName(), prepared.type(), true,
                prepared.items().size(), messageIds.size(), null);
    }

    public DigestSendResult sendTeamByChannel(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        return sendTeam(team.getId());
    }

    private DigestSendResult fail(PreparedDigest prepared, List<DigestMessage> messages,
                                  List<Long> sentMessageIds, String reason, Exception cause) {
        String failureReason = sentMessageIds.isEmpty()
                ? reason
                : reason + " (메시지 " + messages.size() + "개 중 " + sentMessageIds.size() + "개는 발송됨)";
        log.error("발송 실패: team={}, digestId={}, reason={}", prepared.teamName(), prepared.digestId(), failureReason, cause);

        digestWriter.markFailed(prepared, messageIdByItemId(messages, sentMessageIds), failureReason);
        return new DigestSendResult(prepared.teamName(), prepared.type(), false,
                prepared.items().size(), sentMessageIds.size(), failureReason);
    }

    private List<DigestMessage> toMessages(PreparedDigest prepared) {
        if (prepared.type() == DigestType.NO_CHANGE) {
            return List.of(new DigestMessage(null, List.of(DigestBlock.textOnly(prepared.headerText()))));
        }
        List<DigestMessagePlanner.PlannedItem> items = prepared.items().stream()
                .map(item -> new DigestMessagePlanner.PlannedItem(item.itemId(), item.text()))
                .toList();
        return planner.plan(prepared.headerText(), prepared.pmDiscordUserId(), items);
    }

    private static Map<Long, Long> messageIdByItemId(List<DigestMessage> messages, List<Long> messageIds) {
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < messageIds.size(); i++) {
            Long messageId = messageIds.get(i);
            messages.get(i).blocks().stream()
                    .filter(DigestBlock::hasButtons)
                    .forEach(block -> result.put(block.itemId(), messageId));
        }
        return result;
    }
}
