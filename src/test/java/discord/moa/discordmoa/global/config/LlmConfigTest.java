package discord.moa.discordmoa.global.config;

import discord.moa.discordmoa.domain.summary.service.SummaryInputBuilder;
import discord.moa.discordmoa.domain.summary.service.SummaryPrompt;
import discord.moa.discordmoa.domain.summary.service.SummaryResponseParser;
import discord.moa.discordmoa.openai.client.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 application.yml로 LLM 설정이 바인딩되고 관련 빈이 만들어지는지 확인한다 (DB·Discord 없이).
 */
class LlmConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(LlmConfig.class)
            .withBean(SummaryPrompt.class)
            .withBean(SummaryInputBuilder.class)
            .withBean(SummaryResponseParser.class)
            .withBean(OpenAiClient.class);

    @Test
    void application_yml의_설정이_바인딩된다() {
        runner.withPropertyValues("OPENAI_API_KEY=").run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiProperties openAi = context.getBean(OpenAiProperties.class);
            assertThat(openAi.model()).isEqualTo("gpt-6.1-terra");
            assertThat(openAi.timeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(openAi.maxRetries()).isEqualTo(2);
            assertThat(openAi.baseUrl()).isEqualTo("https://api.openai.com/v1");

            SummaryProperties summary = context.getBean(SummaryProperties.class);
            assertThat(summary.promptVersion()).isEqualTo("v1");
            assertThat(summary.maxInputChars()).isEqualTo(60_000);
            assertThat(summary.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
            assertThat(summary.excludedFiles()).contains("**/.env", "**/*.pem");
            assertThat(summary.patchOmittedFiles()).contains("**/package-lock.json", "**/node_modules/**");

            assertThat(context.getBean(SummaryPrompt.class).text()).startsWith("너는 해커톤 팀의");
            assertThat(context.getBean(OpenAiClient.class).isConfigured()).isFalse();
        });
    }

    @Test
    void API_키는_환경_변수에서_읽는다() {
        runner.withPropertyValues("OPENAI_API_KEY=test-key").run(context ->
                assertThat(context.getBean(OpenAiClient.class).isConfigured()).isTrue());
    }

    @Test
    void 프롬프트_버전_파일이_없으면_시작에_실패한다() {
        runner.withPropertyValues("summary.prompt-version=v999").run(context ->
                assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("prompts/summary-v999.txt"));
    }
}
