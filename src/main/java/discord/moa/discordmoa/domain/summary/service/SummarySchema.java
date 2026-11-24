package discord.moa.discordmoa.domain.summary.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured Outputs(strict)용 JSON Schema.
 * strict 모드 규칙: 모든 필드를 required에 넣고 additionalProperties는 false. 값이 없을 수 있는 필드는 ["타입", "null"].
 */
final class SummarySchema {

    static final String NAME = "pm_summary";

    private SummarySchema() {
    }

    /**
     * @param withPreviousItems true면 항목에 relatedPreviousItemId(정수 또는 null)를 둔다 (프롬프트 v2부터)
     * @param withEndpoints     true면 항목에 endpoints("METHOD /path" 문자열 배열, 없으면 빈 배열)를 둔다 (프롬프트 v4부터)
     */
    static Map<String, Object> schema(boolean withPreviousItems, boolean withEndpoints) {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("status", Map.of("type", "string", "enum", List.of("READY", "PARTIAL", "INTERNAL")));
        itemProperties.put("title", Map.of("type", "string"));
        itemProperties.put("commitShas", Map.of("type", "array", "items", Map.of("type", "string")));
        if (withPreviousItems) {
            itemProperties.put("relatedPreviousItemId", Map.of("type", List.of("integer", "null")));
        }
        if (withEndpoints) {
            // strict 모드라 required에 넣는다. "선택"은 빈 배열로 표현한다
            itemProperties.put("endpoints", Map.of("type", "array", "items", Map.of("type", "string")));
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProperties);
        item.put("required", List.copyOf(itemProperties.keySet()));
        item.put("additionalProperties", false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("items", Map.of("type", "array", "items", item)));
        root.put("required", List.of("items"));
        root.put("additionalProperties", false);
        return root;
    }
}
