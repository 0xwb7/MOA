package discord.moa.discordmoa.domain.team.entity;

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
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackedRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(nullable = false, unique = true)
    private Long githubRepoId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String defaultBranch;

    @Column(length = 40)
    private String lastProcessedSha;

    @Column(nullable = false)
    private Long installationId;

    /**
     * /레포해제로 비활성화되면 false. 과거 배치·발송 항목이 이 행을 참조해서 지우지 않는다.
     * DB 기본값 1: ddl-auto update로 컬럼이 추가될 때 기존 행이 활성(1)으로 채워지게 한다
     * (기본값이 없으면 MySQL이 NOT NULL bit 컬럼의 기존 행을 0으로 채워 전부 비활성이 된다).
     */
    @ColumnDefault("1")
    @Column(nullable = false)
    private boolean active = true;

    private Instant deactivatedAt;

    // 등록(또는 다시 등록)한 시각. 이 컬럼이 생기기 전에 등록한 레포는 null
    private Instant registeredAt;

    // 마지막으로 GitHub에서 변경을 확인한 시각 (변경이 없어도 갱신). 아직 없으면 null
    private Instant lastCollectedAt;

    public TrackedRepo(Team team, Long githubRepoId, String fullName, String defaultBranch,
                       String lastProcessedSha, Long installationId) {
        this(team, githubRepoId, fullName, defaultBranch, lastProcessedSha, installationId, null);
    }

    public TrackedRepo(Team team, Long githubRepoId, String fullName, String defaultBranch,
                       String lastProcessedSha, Long installationId, Instant registeredAt) {
        this.team = team;
        this.githubRepoId = githubRepoId;
        this.fullName = fullName;
        this.defaultBranch = defaultBranch;
        this.lastProcessedSha = lastProcessedSha;
        this.installationId = installationId;
        this.registeredAt = registeredAt;
    }

    /**
     * App을 다시 설치하면 설치 ID가 바뀐다. DB 반영은 TrackedRepoRepository.updateInstallationId로 한다.
     */
    public void changeInstallationId(Long installationId) {
        this.installationId = installationId;
    }
}
