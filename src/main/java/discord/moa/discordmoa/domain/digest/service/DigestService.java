package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import discord.moa.discordmoa.domain.digest.sender.DigestSendException;
import discord.moa.discordmoa.domain.digest.sender.DigestSender;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.summary.service.ChangeSummarizer;
import discord.moa.discordmoa.domain.summary.service.SummaryResult;
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
    private final ChangeSummarizer changeSummarizer;

    public DigestSendResult sendTeam(Long teamId) {
        digestWriter.checkNotInProgress(teamId, Instant.now());
        SummaryResult summary = changeSummarizer.summarize(teamId, LlmCallPurpose.DIGEST);   // 트랜잭션 밖 (최대 수 분)
        PreparedDigest prepared = digestWriter.prepare(teamId, Instant.now(), summary);
        String summaryFallbackReason = prepared.type() == DigestType.FALLBACK ? fallbackReason(summary) : null;
        List<DigestMessage> messages = toMessages(prepared);

        List<Long> messageIds;
        try {
            messageIds = digestSender.send(prepared.channelId(), messages);
        } catch (DigestSendException e) {
            return fail(prepared, messages, e.getSentMessageIds(), e.getMessage(), summaryFallbackReason, e);
        } catch (RuntimeException e) {
            return fail(prepared, messages, List.of(), "예상하지 못한 오류: " + e.getMessage(), summaryFallbackReason, e);
        }

        try {
            digestWriter.markSent(prepared, messageIdByItemId(messages, messageIds), Instant.now());
        } catch (RuntimeException e) {
            log.error("발송은 됐지만 결과 기록에 실패했어요: team={}, digestId={}, messageIds={}",
                    prepared.teamName(), prepared.digestId(), messageIds, e);
            throw e;
        }
        return new DigestSendResult(prepared.teamName(), prepared.type(), true,
                prepared.items().size(), messageIds.size(), null, summaryFallbackReason);
    }

    public DigestSendResult sendTeamByChannel(Long channelId) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException("팀 채팅방에서 실행해 주세요. 이 채널은 팀으로 등록돼 있지 않아요."));
        return sendTeam(team.getId());
    }

    private DigestSendResult fail(PreparedDigest prepared, List<DigestMessage> messages,
                                  List<Long> sentMessageIds, String reason, String summaryFallbackReason,
                                  Exception cause) {
        String failureReason = sentMessageIds.isEmpty()
                ? reason
                : reason + " (메시지 " + messages.size() + "개 중 " + sentMessageIds.size() + "개는 발송됨)";
        log.error("발송 실패: team={}, digestId={}, reason={}", prepared.teamName(), prepared.digestId(), failureReason, cause);

        digestWriter.markFailed(prepared, messageIdByItemId(messages, sentMessageIds), failureReason);
        return new DigestSendResult(prepared.teamName(), prepared.type(), false,
                prepared.items().size(), sentMessageIds.size(), failureReason, summaryFallbackReason);
    }

    private static String fallbackReason(SummaryResult summary) {
        if (!summary.hasChanges()) {
            return "요약을 시작한 뒤에 수집된 변경이라 요약하지 않았어요";
        }
        return summary.succeeded() ? "요약하는 동안 배치 상태가 바뀌었어요" : summary.fallbackReason();
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
