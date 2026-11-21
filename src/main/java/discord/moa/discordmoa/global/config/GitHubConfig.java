package discord.moa.discordmoa.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GitHubHttpProperties.class)
public class GitHubConfig {

    public static final String GITHUB_API_URL = "https://api.github.com";

    /**
     * GitHubClient와 GitHubAppTokenProvider가 함께 쓰는 RestClient.
     * GitHub가 응답하지 않아도 호출 스레드가 무한정 묶이지 않도록 타임아웃을 건다.
     */
    @Bean
    public RestClient gitHubRestClient(GitHubHttpProperties properties) {
        return gitHubRestClient(GITHUB_API_URL, properties.connectTimeout(), properties.readTimeout());
    }

    public static RestClient gitHubRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
