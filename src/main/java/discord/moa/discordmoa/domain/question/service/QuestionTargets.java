package discord.moa.discordmoa.domain.question.service;

import java.util.List;

/**
 * 질문을 받을 개발자.
 *
 * @param mentionUserIds 멘션(알림)할 Discord 사용자 ID. 중복 없음, 질문한 사람 제외
 * @param unlinkedNames  Discord 계정이 연동되지 않은 커밋 작성자 이름(GitHub 로그인, 없으면 커밋 작성자 이름). 중복 없음
 */
public record QuestionTargets(List<Long> mentionUserIds, List<String> unlinkedNames) {

    public QuestionTargets {
        mentionUserIds = List.copyOf(mentionUserIds);
        unlinkedNames = List.copyOf(unlinkedNames);
    }
}
