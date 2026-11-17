package discord.moa.discordmoa.domain.team.entity;

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
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private Long discordChannelId;

    @Column(nullable = false)
    private Long pmDiscordUserId;

    public Team(String name, Long discordChannelId, Long pmDiscordUserId) {
        this.name = name;
        this.discordChannelId = discordChannelId;
        this.pmDiscordUserId = pmDiscordUserId;
    }

    public void update(Long discordChannelId, Long pmDiscordUserId) {
        this.discordChannelId = discordChannelId;
        this.pmDiscordUserId = pmDiscordUserId;
    }
}
