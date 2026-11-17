package discord.moa.discordmoa.domain.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long discordUserId;

    @Column(nullable = false, unique = true)
    private Long githubUserId;

    @Column(nullable = false)
    private String githubLogin;

    public AccountLink(Long discordUserId, Long githubUserId, String githubLogin) {
        this.discordUserId = discordUserId;
        this.githubUserId = githubUserId;
        this.githubLogin = githubLogin;
    }

    public void relink(Long githubUserId, String githubLogin) {
        this.githubUserId = githubUserId;
        this.githubLogin = githubLogin;
    }
}
