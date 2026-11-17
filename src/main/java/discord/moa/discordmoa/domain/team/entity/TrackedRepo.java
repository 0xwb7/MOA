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

    public TrackedRepo(Team team, Long githubRepoId, String fullName, String defaultBranch,
                       String lastProcessedSha, Long installationId) {
        this.team = team;
        this.githubRepoId = githubRepoId;
        this.fullName = fullName;
        this.defaultBranch = defaultBranch;
        this.lastProcessedSha = lastProcessedSha;
        this.installationId = installationId;
    }
}
