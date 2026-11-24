package discord.moa.discordmoa.domain.question.entity;

import discord.moa.discordmoa.domain.digest.entity.DigestItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 발송 항목에 대해 "개발자에게 질문하기"로 팀 채널에 올린 질문. 게시에 성공한 질문만 저장한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_digest_question_item_asker_created",
        columnList = "digest_item_id, asker_discord_user_id, created_at"))
public class DigestQuestion {

    public static final int CONTENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "digest_item_id", nullable = false)
    private DigestItem digestItem;

    @Column(nullable = false)
    private Long askerDiscordUserId;

    // 사용자가 쓴 그대로(앞뒤 공백만 제거). 팀 채널 메시지에는 이스케이프하고 필요하면 잘라서 올린다
    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    @Column(nullable = false)
    private Long postedMessageId;

    @Column(nullable = false)
    private Instant createdAt;

    public DigestQuestion(DigestItem digestItem, Long askerDiscordUserId, String content, Long postedMessageId,
                          Instant createdAt) {
        this.digestItem = digestItem;
        this.askerDiscordUserId = askerDiscordUserId;
        this.content = content;
        this.postedMessageId = postedMessageId;
        this.createdAt = createdAt;
    }
}
