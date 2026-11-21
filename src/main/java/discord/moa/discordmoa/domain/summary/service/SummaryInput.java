package discord.moa.discordmoa.domain.summary.service;

import java.util.Set;

/**
 * @param text                LLM에 보낼 입력. overLimit이면 상한을 넘은 상태 그대로다
 * @param knownShas           입력에 포함된 모든 커밋 SHA (응답 검증용)
 * @param droppedPatchCount   상한 때문에 patch를 뺀 파일 수
 * @param messagesShortened   상한 때문에 커밋 메시지를 첫 줄로 줄였는지
 * @param overLimit           줄여도 상한을 넘었는지
 */
public record SummaryInput(
        String text,
        Set<String> knownShas,
        int droppedPatchCount,
        boolean messagesShortened,
        boolean overLimit
) {
}
