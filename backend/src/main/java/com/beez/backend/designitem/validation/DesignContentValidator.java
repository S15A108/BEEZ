package com.beez.backend.designitem.validation;

import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** User-confirmed content v1. Unknown JSON fields are retained, not silently dropped. */
@Component
public class DesignContentValidator {
    public static final Set<String> KINDS = Set.of("REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
    public String validate(String kind, int version, Map<String, Object> content) {
        if (!KINDS.contains(kind) || version != 1 || content == null) {
            throw invalid("지원하는 항목 종류와 schema_version=1이 필요합니다.");
        }
        String description = text(content, "description");
        if (description.length() > 10000) {
            throw invalid("description은 10,000자 이하입니다.");
        }
        if ("API_CONTRACT".equals(kind)) {
            String method = text(content, "method");
            String path = text(content, "path");
            if (!METHODS.contains(method) || !path.startsWith("/") || path.length() > 2000
                    || path.contains("?") || path.contains("#") || path.chars().anyMatch(Character::isWhitespace)
                    || path.chars().anyMatch(Character::isISOControl)) {
                throw invalid("API method는 대문자 HTTP 메서드, path는 /로 시작하는 공백·쿼리 없는 경로입니다.");
            }
        }
        if ("DATA_CONTRACT".equals(kind)) {
            text(content, "entity");
            if (!(content.get("fields") instanceof List<?>)) {
                throw invalid("DATA_CONTRACT.fields 배열이 필요합니다.");
            }
        }
        canonical(content);
        return description;
    }

    private String text(Map<String, Object> content, String key) {
        if (!(content.get(key) instanceof String text) || text.isBlank()) {
            throw invalid(key + " 문자열이 필요합니다.");
        }
        return text;
    }
}
