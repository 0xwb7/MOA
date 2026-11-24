package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.global.config.SummaryProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * resources/prompts/summary-{version}.txt를 읽어 둔다. 파일이 없으면 서버 시작 시 바로 실패한다.
 */
@Component
public class SummaryPrompt {

    private final String version;
    private final String text;

    public SummaryPrompt(SummaryProperties properties) {
        this.version = properties.promptVersion();
        String path = "prompts/summary-" + version + ".txt";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("요약 프롬프트 파일이 없어요: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            this.text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("요약 프롬프트 파일을 읽지 못했어요: " + path, e);
        }
        if (text.isEmpty()) {
            throw new IllegalStateException("요약 프롬프트 파일이 비어 있어요: " + path);
        }
    }

    public String version() {
        return version;
    }

    public String text() {
        return text;
    }

    /**
     * v2부터 이전 회차에 보고한 항목을 입력에 넣고 응답에 relatedPreviousItemId를 받는다. v1은 기존 동작 그대로.
     */
    public boolean usesPreviousItems() {
        return !"v1".equals(version);
    }

    /**
     * v4부터 항목마다 API 주소(endpoints)를 받아 저장하고, 이전 항목 줄에 "| 주소: …"를 붙인다.
     * 서버 주소와 화면이 호출하는 주소가 같은지로 READY를 판단하게 하기 위함이다. v2·v3는 기존 동작 그대로.
     */
    public boolean usesEndpoints() {
        return versionNumber() >= 4;
    }

    private int versionNumber() {
        if (version == null || !version.matches("v\\d+")) {
            return 0;
        }
        return Integer.parseInt(version.substring(1));
    }
}
