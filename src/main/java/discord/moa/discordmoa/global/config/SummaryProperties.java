package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.List;

@ConfigurationProperties("summary")
public record SummaryProperties(
        String promptVersion,           // resources/prompts/summary-{promptVersion}.txt
        int maxInputChars,              // 팀 하나의 LLM 입력 상한 (문자 수)
        int dailyMaxCalls,              // 하루 전체 LLM 호출 수 상한 (재시도·미리보기 포함)
        long dailyMaxTokens,            // 하루 전체 입력+출력 토큰 상한
        ZoneId zone,                    // "하루"의 기준 시간대
        List<String> excludedFiles,     // 이름도 patch도 LLM에 보내지 않을 파일 (Ant 패턴, 대소문자 무시)
        List<String> patchOmittedFiles  // 이름과 줄 수만 보내고 patch는 뺄 파일 (Ant 패턴, 대소문자 무시)
) {
}
