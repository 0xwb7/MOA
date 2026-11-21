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
}
