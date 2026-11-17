package discord.moa.discordmoa.domain.digest.entity;

import discord.moa.discordmoa.domain.team.entity.Team;
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

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Digest {

    private static final int FAILURE_REASON_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DigestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DigestStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    @Column(length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    public Digest(Team team, DigestType type, Instant createdAt) {
        this.team = team;
        this.type = type;
        this.status = DigestStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markSent(Instant sentAt) {
        this.status = DigestStatus.SENT;
        this.sentAt = sentAt;
    }

    public void markFailed(String reason) {
        this.status = DigestStatus.FAILED;
        this.failureReason = reason != null && reason.length() > FAILURE_REASON_MAX_LENGTH
                ? reason.substring(0, FAILURE_REASON_MAX_LENGTH)
                : reason;
    }
}
