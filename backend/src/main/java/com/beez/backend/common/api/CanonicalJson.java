package com.beez.backend.common.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/**
 * 해시용 정규 JSON. 객체의 키를 이름순으로 정렬하고 목록 순서는 그대로 둔다.
 * Map.toString() 과 달리 문자열과 목록의 경계가 남는다 — ["a, b"] 와 ["a", "b"] 가 다른 값이 된다.
 * 패킷 해시(-166)와 MCP 같은 키 재시도의 입력 비교에 쓴다.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** value 를 정규 JSON 문자열로. convertValue 는 객체를 Map·List·기본값으로 바꾸는 Jackson 기능이다 */
    public static String write(ObjectMapper json, Object value) {
        Object tree = json.convertValue(value, Object.class);
        // writeValueAsString: Map·List 를 JSON 문자열로 쓴다
        return json.writeValueAsString(sorted(tree));
    }

    /** 정규 JSON 의 SHA-256 (16진수 64자) */
    public static String sha256(ObjectMapper json, Object value) {
        String canonical = write(json, value);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    /** Map 은 키 순서로 정렬(TreeMap), List 는 순서 유지. 안쪽까지 같은 규칙 */
    static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sorted(item));
            }
            return out;
        }
        return value;
    }
}
