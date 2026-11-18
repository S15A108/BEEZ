package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.invalid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보완 질문 답변 검증 (133 계약 §6 {@code validate_answer} 의 BE 쪽 구현). 질문의 {@code answer_format} 에 맞는 답만 저장한다.
 *
 * <p>입력은 둘 중 하나다. {@code answer_payload} 가 있으면 그 구조를 검증하고 표시용 문장은 서버가 만든다(이때 {@code answer} 문자열은 쓰지 않는다).
 * 문자열 {@code answer} 만 오면 질문 유형에 맞게 구조로 바꾼다: 단답·서술은 그대로, 선택형은 선택지와 정확히 같으면 선택으로,
 * 아니면 "기타"를 허용하는 질문일 때만 기타 입력으로 본다. 유형이 없는 예전 질문은 자유 문장이다.
 */
final class PlanningAnswers {
    static final int OTHER_MAX = 500;
    static final int LEGACY_MAX = 4000;

    record Normalized(String display, Map<String, Object> payload) { }

    private PlanningAnswers() { }

    /**
     * 구조 답의 글자 수(코드 포인트): 선택지와 기타 입력의 합, 또는 본문. AI 가 related_answers 상한을 셀 때(answer_chars)와 같은 방식이다.
     * 질문 생성의 known_questions 와 초안의 related_answers 가 같은 크기로 세야 앞의 범위가 뒤에 들어간다(S15P31A108-414).
     */
    static int chars(Map<String, Object> payload) {
        int total = 0;
        if (payload.get("selected") instanceof List<?> selected) {
            for (Object option : selected) total += codePoints(option);
        }
        return total + codePoints(payload.get("other_text")) + codePoints(payload.get("text"));
    }

    private static int codePoints(Object value) {
        return value instanceof String s ? s.codePointCount(0, s.length()) : 0;
    }

    static Normalized normalize(String questionType, Map<String, Object> format, String answer, Map<String, Object> payload) {
        if (questionType == null) {
            String text = requireText(answer, LEGACY_MAX, "답변");
            return new Normalized(text, null);
        }
        if (payload == null) payload = fromText(questionType, format, requireText(answer, Integer.MAX_VALUE, "답변"));
        if (!questionType.equals(payload.get("type"))) throw invalid("답변 유형이 질문 유형과 다릅니다.");
        return switch (questionType) {
            case "CHOICE" -> choice(format, payload);
            case "SHORT_ANSWER" -> text(format, payload, 200, 500);
            case "ESSAY" -> text(format, payload, 2000, 4000);
            default -> throw invalid("지원하지 않는 질문 유형입니다.");
        };
    }

    private static Map<String, Object> fromText(String type, Map<String, Object> format, String answer) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        if (!"CHOICE".equals(type)) {
            payload.put("text", answer);
            return payload;
        }
        if (options(format).contains(answer)) {
            payload.put("selected", List.of(answer));
        } else {
            payload.put("selected", List.of());
            payload.put("other_text", answer);
        }
        return payload;
    }

    private static Normalized choice(Map<String, Object> format, Map<String, Object> payload) {
        List<String> options = options(format);
        boolean multiple = Boolean.TRUE.equals(format.get("multiple"));
        boolean allowOther = !Boolean.FALSE.equals(format.get("allow_other"));
        List<String> selected = new ArrayList<>();
        Object raw = payload.get("selected");
        if (raw != null && !(raw instanceof List<?>)) throw invalid("selected는 배열이어야 합니다.");
        for (Object value : raw == null ? List.of() : (List<?>) raw) {
            if (!(value instanceof String s)) throw invalid("selected는 문자열 배열이어야 합니다.");
            selected.add(s);
        }
        Set<String> unique = new HashSet<>(selected);
        if (unique.size() != selected.size()) throw invalid("같은 선택지를 두 번 고를 수 없습니다.");
        if (!options.containsAll(selected)) throw invalid("선택지에 없는 값입니다.");
        if (!multiple && selected.size() > 1) throw invalid("하나만 고를 수 있는 질문입니다.");
        String other = null;
        if (payload.get("other_text") != null) {
            if (!allowOther) throw invalid("기타 입력을 받지 않는 질문입니다.");
            other = requireText(payload.get("other_text") instanceof String s ? s : null, OTHER_MAX, "기타 입력");
        }
        if (selected.isEmpty() && other == null) throw invalid("선택하거나 기타 내용을 입력해 주세요.");
        var clean = new LinkedHashMap<String, Object>();
        clean.put("type", "CHOICE");
        clean.put("selected", selected);
        if (other != null) clean.put("other_text", other);
        var shown = new ArrayList<>(selected);
        if (other != null) shown.add(other);
        return new Normalized(String.join(", ", shown), clean);
    }

    private static Normalized text(Map<String, Object> format, Map<String, Object> payload, int defaultMax, int hardMax) {
        int max = format.get("max_length") instanceof Number n ? Math.min(n.intValue(), hardMax) : defaultMax;
        String text = requireText(payload.get("text") instanceof String s ? s : null, max, "답변");
        var clean = new LinkedHashMap<String, Object>();
        clean.put("type", payload.get("type"));
        clean.put("text", text);
        return new Normalized(text, clean);
    }

    @SuppressWarnings("unchecked")
    private static List<String> options(Map<String, Object> format) {
        return format.get("options") instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static String requireText(String value, int max, String label) {
        if (value == null || value.isBlank()) throw invalid(label + "을(를) 입력해 주세요.");
        if (value.codePointCount(0, value.length()) > max) throw invalid(label + "은(는) " + max + "자 이하여야 합니다.");
        if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalid(label + "에 제어 문자를 쓸 수 없습니다.");
        }
        return value;
    }
}
