package discord.moa.discordmoa.domain.question.service;

/**
 * 질문 제출 검사 결과. READY일 때만 post가 있다.
 */
public record QuestionPreparation(Status status, QuestionPost post) {

    public enum Status {
        READY,
        INVALID_CONTENT,   // 앞뒤 공백을 빼고 5자 미만이거나 1000자 초과
        NOT_FOUND,         // 항목이 없음
        TOO_SOON           // 같은 사람이 같은 항목에 60초 안에 이미 질문함
    }

    public static QuestionPreparation of(Status status) {
        return new QuestionPreparation(status, null);
    }

    public static QuestionPreparation ready(QuestionPost post) {
        return new QuestionPreparation(Status.READY, post);
    }
}
