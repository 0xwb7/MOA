package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.openai.dto.OpenAiErrorResponse;
import discord.moa.discordmoa.openai.dto.OpenAiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 응답 본문에서 요약 결과를 꺼내고 검증한다. 규칙을 어기면 SummaryValidationException.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryResponseParser {

    static final int MAX_TITLE_LENGTH = 200;

    private final JsonMapper jsonMapper;

    public OpenAiResponse readEnvelope(String body) {
        try {
            OpenAiResponse response = jsonMapper.readValue(body, OpenAiResponse.class);
            if (response == null) {
                throw new SummaryValidationException("응답 본문이 비어 있어요");
            }
            return response;
        } catch (JacksonException e) {
            throw new SummaryValidationException("응답 JSON을 읽지 못했어요", e);
        }
    }

    public String outputText(OpenAiResponse response) {
        if (!"completed".equals(response.status())) {
            String reason = response.incompleteDetails() == null ? null : response.incompleteDetails().reason();
            throw new SummaryValidationException("응답이 완료되지 않았어요 (status=" + response.status()
                    + (reason == null ? "" : ", reason=" + reason) + ")");
        }

        StringBuilder text = new StringBuilder();
        if (response.output() != null) {
            for (OpenAiResponse.Output output : response.output()) {
                if (!"message".equals(output.type()) || output.content() == null) {
                    continue;
                }
                for (OpenAiResponse.Content content : output.content()) {
                    if ("refusal".equals(content.type())) {
                        throw new SummaryValidationException("모델이 요약을 거절했어요: " + content.refusal());
                    }
                    if ("output_text".equals(content.type()) && content.text() != null) {
                        text.append(content.text());
                    }
                }
            }
        }
        if (text.isEmpty()) {
            throw new SummaryValidationException("응답에 결과 텍스트가 없어요");
        }
        return text.toString();
    }

    /**
     * OpenAI 오류 응답에서 사람이 읽을 메시지를 꺼낸다. 형식이 다르면 null.
     */
    public String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            OpenAiErrorResponse response = jsonMapper.readValue(body, OpenAiErrorResponse.class);
            return response == null || response.error() == null ? null : response.error().message();
        } catch (JacksonException e) {
            return null;
        }
    }

    public List<SummaryItem> parseItems(String outputText, Set<String> knownShas) {
        return parseItems(outputText, knownShas, Set.of());
    }

    /**
     * @param knownPreviousIds 입력에 넣은 이전 항목 ID. 응답의 relatedPreviousItemId가 여기 없으면 그 값만 null로 바꾼다
     * @return READY/PARTIAL은 응답 순서 그대로, INTERNAL은 하나로 합쳐 맨 뒤
     */
    public List<SummaryItem> parseItems(String outputText, Set<String> knownShas, Set<Long> knownPreviousIds) {
        RawSummary raw;
        try {
            raw = jsonMapper.readValue(outputText, RawSummary.class);
        } catch (JacksonException e) {
            throw new SummaryValidationException("스키마와 다른 응답이에요: JSON을 해석하지 못했어요", e);
        }
        if (raw == null || raw.items() == null || raw.items().isEmpty()) {
            throw new SummaryValidationException("요약 항목(items)이 비어 있어요");
        }

        List<SummaryItem> features = new ArrayList<>();
        List<String> internalTitles = new ArrayList<>();
        Set<String> internalShas = new LinkedHashSet<>();
        for (RawItem item : raw.items()) {
            SummaryStatus status = status(item);
            String title = title(item);
            List<String> shas = shas(item, knownShas);
            if (status == SummaryStatus.INTERNAL) {
                internalTitles.add(title);
                internalShas.addAll(shas);
            } else {
                features.add(new SummaryItem(status, title, shas, relatedPreviousItemId(item, knownPreviousIds)));
            }
        }

        List<SummaryItem> result = new ArrayList<>(features);
        if (!internalTitles.isEmpty()) {
            result.add(new SummaryItem(SummaryStatus.INTERNAL,
                    clip(String.join(", ", internalTitles)), List.copyOf(internalShas)));
        }
        return result;
    }

    private static SummaryStatus status(RawItem item) {
        if (item == null || item.status() == null) {
            throw new SummaryValidationException("스키마와 다른 응답이에요: status가 없어요");
        }
        try {
            return SummaryStatus.valueOf(item.status());
        } catch (IllegalArgumentException e) {
            throw new SummaryValidationException("스키마와 다른 응답이에요: 알 수 없는 status '" + item.status() + "'");
        }
    }

    private static String title(RawItem item) {
        if (item.title() == null || item.title().isBlank()) {
            throw new SummaryValidationException("스키마와 다른 응답이에요: title이 비어 있어요");
        }
        return clip(item.title().strip());
    }

    private static List<String> shas(RawItem item, Set<String> knownShas) {
        if (item.commitShas() == null || item.commitShas().isEmpty()) {
            throw new SummaryValidationException("스키마와 다른 응답이에요: '" + item.title() + "' 항목에 commitShas가 없어요");
        }
        Set<String> shas = new LinkedHashSet<>();
        for (String sha : item.commitShas()) {
            String trimmed = sha == null ? "" : sha.strip();
            if (!knownShas.contains(trimmed)) {
                throw new SummaryValidationException("입력에 없는 커밋 SHA가 응답에 있어요: '" + trimmed + "'");
            }
            shas.add(trimmed);
        }
        return List.copyOf(shas);
    }

    /**
     * SHA와 달리 실패시키지 않는다: 잘못된 참고 ID는 PM에게 보이는 내용을 틀리게 만들지 않기 때문이다.
     */
    private static Long relatedPreviousItemId(RawItem item, Set<Long> knownPreviousIds) {
        Long id = item.relatedPreviousItemId();
        if (id == null) {
            return null;
        }
        if (!knownPreviousIds.contains(id)) {
            log.warn("응답의 relatedPreviousItemId가 입력에 없는 이전 항목이라 무시해요: id={}, title={}", id, item.title());
            return null;
        }
        return id;
    }

    private static String clip(String title) {
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        int end = MAX_TITLE_LENGTH;
        if (Character.isHighSurrogate(title.charAt(end - 1))) {
            end--;
        }
        return title.substring(0, end) + "…";
    }

    record RawSummary(List<RawItem> items) {
    }

    record RawItem(String status, String title, List<String> commitShas, Long relatedPreviousItemId) {
    }
}
