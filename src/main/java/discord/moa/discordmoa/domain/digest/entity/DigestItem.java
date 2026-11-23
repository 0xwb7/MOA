package discord.moa.discordmoa.domain.digest.entity;

import discord.moa.discordmoa.domain.summary.service.SummaryStatus;
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

    // LLM 항목만 채운다 (FALLBACK 항목과 이전 행은 null). 다음 회차 요약에 "이전에 보고한 기능"으로 넣는다.
    // 상태는 enum 매핑 대신 이름(문자열)으로 저장한다: Hibernate가 enum 컬럼에 붙이는 check 제약 때문에
    // 값을 추가할 때 직접 마이그레이션해야 하는 문제를 피하기 위함 (ADR-003 운영 메모)
    @Column(length = 20)
    private String itemStatus;

    @Column(length = 300)
    private String title;

    // 이 항목이 상태를 올리거나 이어받은 이전 항목(DigestItem ID). 지표용이라 외래키는 걸지 않는다
    private Long relatedPreviousItemId;

    public DigestItem(Digest digest, TrackedRepo trackedRepo, String text, List<String> commitShas) {
        this.digest = digest;
        this.trackedRepo = trackedRepo;
        this.text = text;
        this.commitShas = commitShas;
    }

    /**
     * LLM 요약 항목.
     */
    public DigestItem(Digest digest, TrackedRepo trackedRepo, String text, List<String> commitShas,
                      SummaryStatus itemStatus, String title, Long relatedPreviousItemId) {
        this(digest, trackedRepo, text, commitShas);
        this.itemStatus = itemStatus.name();
        this.title = title;
        this.relatedPreviousItemId = relatedPreviousItemId;
    }

    public SummaryStatus getItemStatus() {
        return itemStatus == null ? null : SummaryStatus.valueOf(itemStatus);
    }

    public void markDelivered(Long discordMessageId) {
        this.discordMessageId = discordMessageId;
    }
}
