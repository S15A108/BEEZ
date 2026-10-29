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
    static ApiException conflict(String code) {
        String message = switch (code) {
            case "idempotency_conflict" -> "같은 요청 키로 다른 내용이 전송되었습니다. 새 요청 키로 다시 시도해 주세요.";
            case "version_conflict" -> "기준 문서가 변경되었습니다. 현재 승인 기준을 확인한 뒤 다시 시도해 주세요.";
            case "analysis_running" -> "문서를 분석 중입니다. 분석이 끝난 뒤 다시 확인해 주세요.";
            case "analysis_not_failed" -> "실패한 분석만 재시도할 수 있습니다.";
            case "baseline_not_published" -> "아직 기준 설계가 생성되지 않았습니다.";
            case "index_not_ready" -> "문서 색인이 아직 준비되지 않았습니다. 분석 상태를 확인해 주세요.";
            case "baseline_changed" -> "기준 설계가 변경되었습니다. 최신 기준으로 다시 조회해 주세요.";
            default -> "현재 상태에서는 요청을 처리할 수 없습니다. 최신 상태를 확인해 주세요.";
        };
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
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
