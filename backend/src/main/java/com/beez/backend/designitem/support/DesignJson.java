package com.beez.backend.designitem.support;

import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;

/** Stable JSON, independent of object member order, for optimistic concurrency and snapshots. */
public final class DesignJson {
    public static final JsonMapper JSON = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    public static String canonical(Object value) {
        String result = JSON.writeValueAsString(ordered(JSON.convertValue(value, Object.class), 0));
        if (result.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw invalid("설계 본문이 너무 큽니다.");
        }
        return result;
    }

    private static Object ordered(Object value, int depth) {
        if (depth > 32) {
            throw invalid("설계 본문 중첩이 너무 깊습니다.");
        }
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw invalid("JSON 필드명이 필요합니다.");
                }
                result.put(key, ordered(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> ordered(v, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        throw invalid("JSON 본문 형식이 올바르지 않습니다.");
    }

    public static String hash(Object value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> object(Object value) {
        return JSON.readValue(value.toString(), new TypeReference<Map<String, Object>>() { });
    }

    public static long id(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    public static String time(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (value instanceof OffsetDateTime time) {
            return time.toInstant().toString();
        }
        return value.toString();
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    public static ApiException conflict(String code) {
        String message = switch (code) {
            case "draft_changed" -> "검토한 문서나 설계 항목이 변경되었습니다. 다시 조회하고 검토해 주세요.";
            case "version_conflict" -> "초안이 변경되었거나 수정할 수 없는 상태입니다. 최신 내용을 확인해 주세요.";
            case "baseline_exists" -> "이미 기준 설계가 발행되었습니다. 이후 변경은 변경 제안으로 요청해 주세요.";
            case "idempotency_conflict" -> "같은 요청 키로 다른 내용이 전송되었습니다. 새 요청 키로 다시 시도해 주세요.";
            case "analysis_not_completed" -> "문서 4종의 색인이 아직 준비되지 않았습니다. 분석 상태를 확인해 주세요.";
            case "document_not_approved" -> "문서별 팀장 승인이 모두 완료된 후 기준 설계를 생성할 수 있습니다.";
            case "submission_locked" -> "팀장 검토를 요청한 내용은 잠겨 있습니다. 반려 후 수정할 수 있습니다.";
            case "submission_decided" -> "이미 승인 또는 반려된 제출 건입니다. 최신 검토 상태를 확인해 주세요.";
            case "review_stale" -> "검토 대상이나 참고 문서가 변경되었습니다. 최신 내용으로 다시 검토해 주세요.";
            case "review_in_progress" -> "비비가 검토 중입니다. 같은 요청으로 결과를 다시 확인해 주세요.";
            case "review_decisions_pending" -> "비비의 보완 제안을 반영하거나 넘긴 후 팀장 검토를 요청해 주세요.";
            case "change_proposal_required" -> "기준 설계 생성 이후 문서 수정은 변경 제안을 통해 요청해 주세요.";
            default -> "현재 상태에서는 요청을 처리할 수 없습니다. 최신 정보를 확인해 주세요.";
        };
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private DesignJson() {
    }
}
