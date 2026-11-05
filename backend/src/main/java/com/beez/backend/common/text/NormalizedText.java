package com.beez.backend.common.text;

import java.util.ArrayList;
import java.util.List;

/**
 * utf8-lf-v1 정규화 원문의 줄 규칙 (00-common SourceLocator). BOM 을 없애고 CRLF/CR 을 LF 로 바꾼 뒤 {@code \n} 으로 나눈다.
 * 마지막 줄바꿈 뒤의 빈 조각은 줄이 아니다 — {@code "a\nb\n"} 은 2줄이다. 문서 한 곳에서만 줄을 세어 AI 출력 검증과
 * 줄 편집이 같은 번호를 쓰게 한다.
 */
public final class NormalizedText {
    private NormalizedText() { }

    /** BOM 제거, CRLF/CR → LF. */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 정규화한 뒤의 줄 목록(줄바꿈 문자 제외). 빈 문서는 빈 목록. */
    public static List<String> lines(String text) {
        String s = normalize(text);
        if (s.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        if (s.endsWith("\n")) lines.remove(lines.size() - 1);
        return lines;
    }

    /** 1-based 닫힌 줄 범위를 LF 로 이은 문자열. 범위가 줄 안에 있어야 한다. */
    public static String range(List<String> lines, int start, int end) {
        if (start < 1 || end < start || end > lines.size()) throw new IllegalArgumentException("invalid line range");
        return String.join("\n", lines.subList(start - 1, end));
    }
}
