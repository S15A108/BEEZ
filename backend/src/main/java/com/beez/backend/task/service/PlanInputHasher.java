package com.beez.backend.task.service;

import com.beez.backend.task.entity.PlanRelation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 계획 입력의 SHA-256. 같은 내용이면 순서가 달라도 같은 값이 나오도록 정렬한 뒤 문자열로 만들어 해시한다.
 * 검증 결과 재사용(D6, AC-16)의 키다. 선행 작업 id, 참조(항목·버전·관계) 가 포함되고 제목·접근 방식은 포함하지 않는다 —
 * 제목만 바꾼 계획은 같은 입력으로 본다 (AC-13).
 *
 * <p>JSON 라이브러리 버전에 묶이지 않도록 아주 작은 직렬화 함수를 직접 둔다 (Map·List·문자열·숫자·불리언·null 만 다룬다).
 */
@Component
public class PlanInputHasher {

    public String hash(PlanCommand plan, Map<Long, Long> resolvedVersionByItem, Map<Long, PlanRelation> relationByItem) {
        // TreeMap: 키 순서가 항상 같다 → 같은 내용이면 같은 문자열
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("goal", plan.goal());
        canonical.put("included_scope", sorted(plan.includedScope()));
        canonical.put("excluded_scope", sorted(plan.excludedScope()));
        canonical.put("completion_criteria", sorted(plan.completionCriteria()));
        canonical.put("dependency_task_ids", sortedLongs(plan.dependencyTaskIds()));
        canonical.put("no_contract_change", plan.noContractChange());
        canonical.put("unlinked", plan.unlinked());

        List<Map<String, Object>> refs = new ArrayList<>();
        List<Long> itemIds = new ArrayList<>(relationByItem.keySet());
        Collections.sort(itemIds);
        for (Long itemId : itemIds) {
            Map<String, Object> ref = new TreeMap<>();
            ref.put("item", itemId);
            ref.put("version", resolvedVersionByItem.get(itemId));
            ref.put("relation", relationByItem.get(itemId).name());
            refs.add(ref);
        }
        canonical.put("design_refs", refs);

        StringBuilder sb = new StringBuilder();
        write(sb, canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    /** 정렬된 키 순서로 JSON 비슷한 문자열을 만든다. 사람이 읽을 일은 없고 해시 입력으로만 쓴다. */
    @SuppressWarnings("unchecked")
    private void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sortedMap = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sortedMap.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sortedMap.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
    }

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private List<Long> sortedLongs(List<Long> values) {
        List<Long> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }
}
