package discord.moa.discordmoa.domain.digest.entity;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.global.jpa.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 팀의 발송 회차 하나. 회차는 발송 예정 시각(scheduledFor)으로 식별하고, (팀, scheduledFor)는 유일하다.
 * 발송에 필요한 내용(헤더, 멘션 대상, 항목)은 READY가 될 때 모두 DB에 저장해서, 준비와 발송 사이에 서버가 재시작돼도 보낼 수 있다.
 * enum 컬럼은 MySQL 네이티브 enum(...) 대신 VARCHAR로 저장한다. ddl-auto: update는 네이티브 enum의 값 목록을 바꾸지 않아
 * 새 상태값 저장이 실패하기 때문이다. (Hibernate가 새로 만드는 테이블에는 check (col in (...)) 제약이 붙으므로,
 * 그런 DB에서 enum 값을 추가할 때는 그 check 제약을 직접 고쳐야 한다.)
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_digest_team_scheduled_for", columnNames = {"team_id", "scheduled_for"}))
public class Digest {

    private static final int FAILURE_REASON_MAX_LENGTH = 1000;
    private static final int HEADER_MAX_LENGTH = 2000;
    public static final int UNCOLLECTED_REPOS_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private CycleKind cycleKind;

    // PREPARING 동안에는 아직 정해지지 않아 null
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private DigestType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private DigestStatus status;

    @Column(length = HEADER_MAX_LENGTH)
    private String headerText;

    // 멘션할 PM. NO_CHANGE는 멘션하지 않아 null
    private Long mentionUserId;

    // LLM 요약 대신 변경 목록(FALLBACK)으로 만든 이유
    @Column(length = FAILURE_REASON_MAX_LENGTH)
    private String fallbackReason;

    // 이번 회차 준비 때 수집하지 못한 레포 이름 (JSON 배열). PM 메시지에 "일부 레포를 확인하지 못했어요"로 알린다.
    // 준비 도중(PREPARING) 저장해서 마감 fallback, 발송 시점 즉석 fallback, 재시작 복구 발송에서도 유지된다. 없으면 null
    @Convert(converter = StringListJsonConverter.class)
    @Column(length = UNCOLLECTED_REPOS_MAX_LENGTH)
    private List<String> uncollectedRepos;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readyAt;

    // 발송 선점 시각. 같은 회차를 두 번 보내지 않도록 조건부로 채운다
    private Instant sendStartedAt;

    private Instant sentAt;

    @Column(length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    public Digest(Team team, Instant scheduledFor, CycleKind cycleKind, Instant createdAt) {
        this.team = team;
        this.scheduledFor = scheduledFor;
        this.cycleKind = cycleKind;
        this.status = DigestStatus.PREPARING;
        this.createdAt = createdAt;
    }

    /**
     * READY 전환(조건부 UPDATE) 직후 같은 트랜잭션에서 발송 내용을 채운다.
     */
    public void fillContent(DigestType type, String headerText, Long mentionUserId, String fallbackReason) {
        this.type = type;
        this.headerText = headerText;
        this.mentionUserId = mentionUserId;
        this.fallbackReason = cut(fallbackReason);
    }

    public void markSent(Instant sentAt) {
        this.status = DigestStatus.SENT;
        this.sentAt = sentAt;
    }

    public void markFailed(String reason) {
        this.status = DigestStatus.FAILED;
        this.failureReason = cut(reason);
    }

    public List<String> getUncollectedRepos() {
        return uncollectedRepos == null ? List.of() : uncollectedRepos;
    }

    public boolean isFinished() {
        return status == DigestStatus.SENT || status == DigestStatus.FAILED;
    }

    private static String cut(String text) {
        return text != null && text.length() > FAILURE_REASON_MAX_LENGTH ? text.substring(0, FAILURE_REASON_MAX_LENGTH) : text;
    }
}
