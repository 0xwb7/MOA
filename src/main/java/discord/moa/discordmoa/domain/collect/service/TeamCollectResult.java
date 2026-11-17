package discord.moa.discordmoa.domain.collect.service;

import java.util.List;

public record TeamCollectResult(String teamName, List<RepoCollectResult> repos) {
}
