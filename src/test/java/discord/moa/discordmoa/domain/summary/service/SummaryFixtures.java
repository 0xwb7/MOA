package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.collect.entity.ChangeBatch;
import discord.moa.discordmoa.domain.collect.entity.CommitSnapshot;
import discord.moa.discordmoa.domain.collect.entity.FileChange;
import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import discord.moa.discordmoa.domain.team.entity.Team;
import discord.moa.discordmoa.domain.team.entity.TrackedRepo;
import discord.moa.discordmoa.global.config.SummaryProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class SummaryFixtures {

    static final Instant T = Instant.parse("2026-11-20T03:00:00Z");
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Properties YML = loadYml();

    private SummaryFixtures() {
    }

    /**
     * 일일 상한은 용도(DIGEST, PREVIEW)마다 같은 값으로 둔다. 파일 패턴은 실제 application.yml 값을 그대로 쓴다.
     */
    static SummaryProperties properties(int maxInputChars, int dailyMaxCalls, long dailyMaxTokens) {
        return properties("v1", maxInputChars, dailyMaxCalls, dailyMaxTokens);
    }

    /**
     * @param promptVersion v1: 이번 회차 변경만 / v2: 이전에 보고한 항목도 입력에 넣음
     */
    static SummaryProperties properties(String promptVersion, int maxInputChars, int dailyMaxCalls, long dailyMaxTokens) {
        return new SummaryProperties(promptVersion, maxInputChars,
                Map.of(LlmCallPurpose.DIGEST, dailyMaxCalls, LlmCallPurpose.PREVIEW, dailyMaxCalls),
                Map.of(LlmCallPurpose.DIGEST, dailyMaxTokens, LlmCallPurpose.PREVIEW, dailyMaxTokens),
                SEOUL, ymlList("summary.excluded-files"), ymlList("summary.patch-omitted-files"), null);
    }

    static Team team() {
        Team team = new Team("1팀", 100L, 7L);
        ReflectionTestUtils.setField(team, "id", 10L);
        return team;
    }

    static TrackedRepo repo(Long id, String fullName) {
        TrackedRepo repo = new TrackedRepo(team(), id * 100, fullName, "main", null, 555L);
        ReflectionTestUtils.setField(repo, "id", id);
        return repo;
    }

    static ChangeBatch batch(Long id, TrackedRepo repo, List<CommitSnapshot> commits, List<FileChange> files) {
        ChangeBatch batch = new ChangeBatch(repo, "base", "head", T, false, false, commits, files);
        ReflectionTestUtils.setField(batch, "id", id);
        return batch;
    }

    static CommitSnapshot commit(String sha, String message) {
        return new CommitSnapshot(sha, message, "alice", 11L, "Alice", T, "https://github.com/x/y/commit/" + sha);
    }

    static FileChange file(String filename, String patch) {
        return new FileChange(null, filename, "modified", 10, 2, patch, false);
    }

    static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    private static List<String> ymlList(String key) {
        List<String> values = new ArrayList<>();
        for (int i = 0; YML.containsKey(key + "[" + i + "]"); i++) {
            values.add(YML.getProperty(key + "[" + i + "]"));
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("application.yml에 " + key + "가 없어요");
        }
        return values;
    }

    private static Properties loadYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }
}
