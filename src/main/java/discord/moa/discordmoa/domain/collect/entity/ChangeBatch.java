package discord.moa.discordmoa.domain.collect.entity;

import discord.moa.discordmoa.domain.digest.entity.Digest;
import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracked_repo_id", nullable = false)
    private TrackedRepo trackedRepo;

    @Column(length = 40)
    private String baseSha;

    @Column(nullable = false, length = 40)
    private String headSha;

    @Column(nullable = false)
    private Instant collectedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // MySQL 네이티브 enum 대신 VARCHAR (값 추가 시 ddl-auto가 컬럼을 바꾸지 않음, Digest 참고)
    @Column(nullable = false, length = 20)
    private ChangeBatchStatus status;

    @Column(nullable = false)
    private boolean forcePushDetected;

    @Column(nullable = false)
    private boolean truncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<CommitSnapshot> commits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<FileChange> files;

    // 이 배치를 담은 회차 Digest (PREPARED, SENT일 때)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "digest_id")
    private Digest digest;

    // FALLBACK 발송일 때만 채운다. LLM 항목은 한 배치의 커밋이 여러 항목에 나뉠 수 있어서 연결하지 않는다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "digest_item_id")
    private DigestItem digestItem;

    public ChangeBatch(TrackedRepo trackedRepo, String baseSha, String headSha, Instant collectedAt,
                       boolean forcePushDetected, boolean truncated,
                       List<CommitSnapshot> commits, List<FileChange> files) {
        this.trackedRepo = trackedRepo;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.collectedAt = collectedAt;
        this.status = ChangeBatchStatus.COLLECTED;
        this.forcePushDetected = forcePushDetected;
        this.truncated = truncated;
        this.commits = commits;
        this.files = files;
    }

    /**
     * PREPARED 전환은 ChangeBatchRepository의 조건부 UPDATE로 한다. FALLBACK 항목이 만들어지면 연결한다.
     */
    public void linkItem(DigestItem digestItem) {
        this.digestItem = digestItem;
    }

    public void markSent() {
        this.status = ChangeBatchStatus.SENT;
    }

    /**
     * 발송 실패·준비 취소: 다음 회차에 다시 담기도록 되돌리고 연결을 끊는다.
     */
    public void revertToCollected() {
        this.status = ChangeBatchStatus.COLLECTED;
        this.digest = null;
        this.digestItem = null;
    }
}
