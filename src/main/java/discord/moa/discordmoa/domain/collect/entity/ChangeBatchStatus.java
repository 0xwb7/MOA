package discord.moa.discordmoa.domain.collect.entity;

/**
 * COLLECTED → PREPARED(회차 Digest에 담김) → SENT. 발송 실패나 준비 취소 시 COLLECTED로 돌아간다.
 * 레포가 비활성화(해제·자동 정리)되거나 다시 등록되면 그 레포의 COLLECTED는 DISCARDED가 되어 보내지 않는다.
 * DB에는 VARCHAR(20)로 저장한다 (ChangeBatch 참고). 새 값을 추가할 때 ADR-003 운영 메모를 확인할 것.
 */
public enum ChangeBatchStatus {
    COLLECTED,
    PREPARED,
    SENT,
    DISCARDED,
}
