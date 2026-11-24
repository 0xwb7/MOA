package discord.moa.discordmoa.domain.question.service;

import java.util.List;

/**
 * 팀 채널에 올릴 질문. DB 조회가 끝난 뒤 트랜잭션 밖에서 Discord에 게시할 수 있도록 엔티티 대신 값만 담는다.
 *
 * @param replyToMessageId 답글로 달 요약 메시지 ID. 메시지 ID가 없는 항목이면 null
 * @param content          게시할 메시지 본문 (2000자 이내)
 * @param mentionUserIds   실제로 알림을 보낼 개발자. 이 사람들만 멘션이 동작한다
 * @param question         저장할 질문 원문 (앞뒤 공백만 제거)
 */
public record QuestionPost(
        long itemId,
        String teamName,
        Long replyToMessageId,
        String content,
        List<Long> mentionUserIds,
        String question
) {

    public QuestionPost {
        mentionUserIds = List.copyOf(mentionUserIds);
    }
}
