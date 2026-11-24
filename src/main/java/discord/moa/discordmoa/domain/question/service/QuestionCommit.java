package discord.moa.discordmoa.domain.question.service;

/**
 * 질문 메시지에 보여줄 관련 커밋.
 *
 * @param htmlUrl 커밋 정보를 찾지 못했으면 null (링크 없이 SHA만 보여준다)
 */
public record QuestionCommit(String sha, String htmlUrl) {
}
