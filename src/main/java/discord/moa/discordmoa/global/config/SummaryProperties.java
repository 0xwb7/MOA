package discord.moa.discordmoa.global.config;

import discord.moa.discordmoa.domain.summary.entity.LlmCallPurpose;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("summary")
public record SummaryProperties(
        String promptVersion,                           // resources/prompts/summary-{promptVersion}.txt
        int maxInputChars,                              // 팀 하나의 LLM 입력 상한 (문자 수)
        Map<LlmCallPurpose, Integer> dailyMaxCalls,     // 용도별 하루 LLM 호출 수 상한 (재시도 포함)
        Map<LlmCallPurpose, Long> dailyMaxTokens,       // 용도별 하루 입력+출력 토큰 상한
        ZoneId zone,                                    // "하루"의 기준 시간대
        List<String> excludedFiles,                     // 이름도 patch도 LLM에 보내지 않을 파일 (Ant 패턴, 대소문자 무시)
        List<String> patchOmittedFiles                  // 이름과 줄 수만 보내고 patch는 뺄 파일 (Ant 패턴, 대소문자 무시)
) {

    public SummaryProperties {
        requireAllPurposes("summary.daily-max-calls", dailyMaxCalls);
        requireAllPurposes("summary.daily-max-tokens", dailyMaxTokens);
    }

    public int dailyMaxCalls(LlmCallPurpose purpose) {
        return dailyMaxCalls.get(purpose);
    }

    public long dailyMaxTokens(LlmCallPurpose purpose) {
        return dailyMaxTokens.get(purpose);
    }

    private static void requireAllPurposes(String key, Map<LlmCallPurpose, ?> limits) {
        List<LlmCallPurpose> missing = Arrays.stream(LlmCallPurpose.values())
                .filter(purpose -> limits == null || limits.get(purpose) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(key + "에 용도별 상한이 빠졌어요: " + missing
                    + " (예: " + key + ".digest, " + key + ".preview)");
        }
    }
}
