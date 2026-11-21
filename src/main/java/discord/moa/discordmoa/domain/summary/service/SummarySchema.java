package discord.moa.discordmoa.domain.summary.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured Outputs(strict)용 JSON Schema.
 * strict 모드 규칙: 모든 필드를 required에 넣고 additionalProperties는 false.
 */
final class SummarySchema {

    static final String NAME = "pm_summary";

    private SummarySchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("status", Map.of("type", "string", "enum", List.of("READY", "PARTIAL", "INTERNAL")));
        itemProperties.put("title", Map.of("type", "string"));
        itemProperties.put("commitShas", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProperties);
        item.put("required", List.of("status", "title", "commitShas"));
        item.put("additionalProperties", false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("items", Map.of("type", "array", "items", item)));
        root.put("required", List.of("items"));
        root.put("additionalProperties", false);
        return root;
    }
}
