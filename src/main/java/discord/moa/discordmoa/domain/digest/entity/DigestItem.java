package discord.moa.discordmoa.domain.digest.entity;

import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class DigestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "digest_id", nullable = false)
    private Digest digest;

    // LLM 항목은 여러 레포에 걸칠 수 있어서, 커밋이 모두 한 레포일 때만 채운다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tracked_repo_id")
    private TrackedRepo trackedRepo;

    @Column(nullable = false, length = 4000)
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> commitShas;

    private Long discordMessageId;

    // PM이 "확인했어요"를 누른 시각과 누른 사람(Discord 사용자 ID). DigestItemRepository.confirm으로만 채운다
    private Instant confirmedAt;

    private Long confirmedBy;

    public DigestItem(Digest digest, TrackedRepo trackedRepo, String text, List<String> commitShas) {
        this.digest = digest;
        this.trackedRepo = trackedRepo;
        this.text = text;
        this.commitShas = commitShas;
    }

    public void markDelivered(Long discordMessageId) {
        this.discordMessageId = discordMessageId;
    }
}
