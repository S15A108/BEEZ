package com.beez.backend.ai;

import static com.beez.backend.designitem.support.DesignJson.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClientResponseException;

/** AI 오류 응답 본문 읽기. 어댑터들이 같은 규칙으로 쓴다. */
public final class AiErrorBodies {
    private AiErrorBodies() { }

    /**
     * 오류 본문의 usage 배열. 본문이 JSON 객체가 아니거나 usage 가 객체 배열이 아니면 null — 오류 분류는 그대로 두고
     * 사용량만 "미확인"으로 남기게 한다(알 수 없는 값을 0 으로 단정하지 않는다).
     */
    public static List<Map<String, Object>> usageIn(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) return null;
        Object parsed;
        try {
            parsed = JSON.readValue(body, Object.class);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("usage") instanceof List<?> list)) return null;
        List<Map<String, Object>> usage = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) return null;
            @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) m;
            usage.add(entry);
        }
        return usage;
    }
}
