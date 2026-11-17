package discord.moa.discordmoa.domain.collect.entity;

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
}
