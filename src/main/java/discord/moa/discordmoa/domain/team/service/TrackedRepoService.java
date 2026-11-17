package discord.moa.discordmoa.domain.team.service;

import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.domain.team.repository.TeamRepository;
import discord.moa.discordmoa.domain.team.repository.TrackedRepoRepository;
import discord.moa.discordmoa.github.client.GitHubClient;
import discord.moa.discordmoa.github.dto.GitHubRepo;
import discord.moa.discordmoa.github.dto.RepoAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TrackedRepoService {

    private final TrackedRepoRepository trackedRepoRepository;
    private final TeamRepository teamRepository;
    private final GitHubClient gitHubClient;
    private final String installUrl;

    public TrackedRepoService(TrackedRepoRepository trackedRepoRepository,
                              TeamRepository teamRepository,
                              GitHubClient gitHubClient,
                              @Value("${github.install-url}") String installUrl) {
        this.trackedRepoRepository = trackedRepoRepository;
        this.teamRepository = teamRepository;
        this.gitHubClient = gitHubClient;
        this.installUrl = installUrl;
    }

    public TrackedRepo register(Long channelId, String url) {
        Team team = teamRepository.findByDiscordChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "팀 채팅방에서만 레포를 등록할 수 있어요. 이 채널이 팀으로 등록돼 있는지 운영진에게 확인해 주세요."));

        RepoAddress address = RepoAddress.parse(url);

        Long installationId = gitHubClient.findInstallationId(address.owner(), address.repo())
                .orElseThrow(() -> new IllegalStateException(notAccessibleMessage(address)));

        GitHubRepo repo = gitHubClient.findRepo(installationId, address.owner(), address.repo())
                .orElseThrow(() -> new IllegalStateException(notAccessibleMessage(address)));

        if (trackedRepoRepository.existsByGithubRepoId(repo.id())) {
            throw alreadyRegistered(repo);
        }

        String headSha = gitHubClient
                .findHeadSha(installationId, address.owner(), address.repo(), repo.defaultBranch())
                .orElse(null);

        try {
            return trackedRepoRepository.save(new TrackedRepo(
                    team, repo.id(), repo.fullName(), repo.defaultBranch(), headSha, installationId));
        } catch (DataIntegrityViolationException e) {
            throw alreadyRegistered(repo);
        }
    }

    private String notAccessibleMessage(RepoAddress address) {
        return "MOA가 `" + address.fullName() + "` 레포에 접근할 수 없어요.\n"
                + "레포 주인(" + address.owner() + ")이 아래 순서대로 진행해 주세요.\n"
                + "1. 이 링크에서 MOA App을 설치해요: " + installUrl + "\n"
                + "2. 설치 화면의 Repository access에서 이 레포를 선택해요.\n"
                + "   (이미 설치했다면 GitHub Settings → Applications → MOA의 Configure에서 이 레포를 추가해요.)\n"
                + "3. 이 채널에서 /레포등록 을 다시 실행해요.\n"
                + "주소에 오타가 없는지도 함께 확인해 주세요.";
    }

    private IllegalStateException alreadyRegistered(GitHubRepo repo) {
        return new IllegalStateException("이미 등록된 레포예요: " + repo.fullName());
    }
}
