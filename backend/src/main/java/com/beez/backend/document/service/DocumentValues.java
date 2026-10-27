package com.beez.backend.document.service;

import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

final class DocumentValues {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message); }
    static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code, code); }
    static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "not_found", "문서를 찾을 수 없습니다."); }
    static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    static String time(Object value) {
        return value instanceof java.sql.Timestamp ts ? ts.toInstant().toString()
                : value instanceof java.time.OffsetDateTime odt ? odt.toInstant().toString() : value.toString();
    }
    static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static int size(int limit) { if (limit < 1 || limit > 100) throw invalid("limit은 1~100입니다."); return limit; }
    static String cursor(Map<String, Object> row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (time(row.get("created_at")) + "|" + row.get("id") + "|" + row.getOrDefault("type", "")).getBytes(StandardCharsets.UTF_8));
    }
    static String[] cursor(String cursor) {
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 3 || Long.parseLong(values[1]) < 1) throw new IllegalArgumentException();
            Instant.parse(values[0]);
            return values;
        } catch (RuntimeException e) { throw invalid("유효하지 않은 cursor입니다."); }
    }
    static Map<String, Object> page(List<Map<String, Object>> items, boolean more, String cursor) {
        var page = new LinkedHashMap<String, Object>();
        page.put("items", items); page.put("has_more", more);
        if (more) page.put("next_cursor", cursor);
        return page;
    }
    private DocumentValues() { }
}
