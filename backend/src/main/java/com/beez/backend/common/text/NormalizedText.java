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
        String s = !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 정규화한 원문이 LF 로 끝나는지. */
    public static boolean endsWithLf(String text) {
        return normalize(text).endsWith("\n");
    }

    /** 정규화한 뒤의 줄 목록(줄바꿈 문자 제외). 빈 문서는 빈 목록. */
    public static List<String> lines(String text) {
        String s = normalize(text);
        if (s.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        if (s.endsWith("\n")) lines.remove(lines.size() - 1);
        return lines;
    }

    /**
     * 1-based 닫힌 줄 범위의 원문. <b>각 줄의 LF 를 포함한다</b>(AI SourceLocator 와 같은 기준) —
     * {@code "a\nb\n"} 의 1행은 {@code "a\n"}. 문서가 LF 로 끝나지 않으면 마지막 줄만 LF 가 없다.
     */
    public static String range(List<String> lines, boolean endsWithLf, int start, int end) {
        if (start < 1 || end < start || end > lines.size()) throw new IllegalArgumentException("invalid line range");
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < end; i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1 || endsWithLf) sb.append('\n');
        }
        return sb.toString();
    }
}
