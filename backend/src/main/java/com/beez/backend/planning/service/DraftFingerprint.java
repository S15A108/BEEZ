package com.beez.backend.planning.service;

import java.util.Locale;
import java.util.Map;

/**
 * 후보가 이미 있는 항목과 같은지 보는 보수적인 지문(S15P31A108-389). 종류별로 같은 대상을 가리키는 값만 쓴다:
 * API 는 메서드+경로(경로 변수 이름은 무시), 데이터는 엔티티 이름, 나머지는 설명 문장. 표현이 다른 같은 뜻은 잡지 않는다.
 */
final class DraftFingerprint {
    private DraftFingerprint() { }

    static String of(String kind, Map<String, Object> content) {
        String key = switch (kind) {
            case "API_CONTRACT" -> upper(content.get("method")) + " " + path(content.get("path"));
            case "DATA_CONTRACT" -> normalize(content.get("entity"));
            default -> normalize(content.get("description"));
        };
        return key.isBlank() ? null : kind + ":" + key;
    }

    private static String upper(Object value) { return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT); }

    private static String path(Object value) {
        if (value == null) return "";
        return value.toString().trim().toLowerCase(Locale.ROOT).replaceAll("\\{[^}/]*}", "{}").replaceAll("/+$", "");
    }

    static String normalize(Object value) {
        if (value == null) return "";
        String text = value.toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
