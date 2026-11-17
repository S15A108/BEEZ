package com.beez.backend.designitem.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설계 항목 출처 위치(source_locator) 중 줄 범위가 아닌 "인용 없음" 표시를 다룬다(S15P31A108-422).
 *
 * <p>출처 문서는 있지만 그 문서에 인용할 줄이 없는 항목(기획 질문 답변만 근거인 항목 등)이 있다. 위치가 비어 있으면 근거 수집이
 * "근거를 찾지 못함"으로 보아 그 항목을 참조하는 작업 검증이 영구히 막히므로, 사람이 근거 없음을 확인한 항목은 이 표시를 남긴다.
 * 근거 수집은 이 항목을 출처 문서 없는 수동 항목처럼 건너뛴다.
 */
public final class SourceLocators {
    public static final String NO_QUOTE_LOCATION = "원문 인용 없음";
    /** 인용 없음 표시. design_item_version·baseline_item_source 의 source_locator 에 그대로 넣는다. */
    public static final String NO_QUOTE = DesignJson.canonical(Map.of("no_quote", true, "location", NO_QUOTE_LOCATION));
    /** 출처 한 곳의 발췌 상한. 근거 수집(ValidationEvidenceBuilder)이 이보다 큰 발췌를 쓸 수 없는 근거로 본다. */
    public static final int MAX_EXCERPT_BYTES = 16_000;

    private SourceLocators() {
    }

    public static boolean isNoQuote(Map<String, Object> locator) {
        return locator != null && Boolean.TRUE.equals(locator.get("no_quote"));
    }

    /** JSON 글자(없으면 null)가 인용 없음 표시인지. 읽을 수 없는 값은 아니라고 본다. */
    public static boolean isNoQuote(Object locatorJson) {
        if (locatorJson == null) return false;
        try {
            return isNoQuote(DesignJson.object(locatorJson));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * 사람이 지정한 줄 범위의 위치. 표시 문자열은 다른 항목과 같은 "제목 경로 / Ls-Le" 형태로, 시작 줄 위의 마크다운 제목을
     * 단계별로 이어 붙인다(제목이 없으면 "Ls-Le"). 범위가 문서 밖이거나 발췌가 근거 수집 상한을 넘으면 null 이다.
     */
    public static String lines(String content, int start, int end) {
        List<String> all = SourceRemapper.lines(content);
        if (start < 1 || end < start || end > all.size()) return null;
        long bytes = 0;
        for (int i = start - 1; i < end; i++) {
            bytes += all.get(i).getBytes(StandardCharsets.UTF_8).length + 1L; // LF 포함(마지막 줄에 LF 가 없어도 상한 판단엔 무해)
        }
        if (bytes > MAX_EXCERPT_BYTES) return null;
        var locator = new LinkedHashMap<String, Object>();
        locator.put("type", "text_lines");
        locator.put("start_line", start);
        locator.put("end_line", end);
        String path = headingPath(all, start - 1);
        locator.put("location", (path.isEmpty() ? "" : path + " / ") + "L" + start + "-L" + end);
        return DesignJson.canonical(locator);
    }

    /** before0 줄(0-기반) 위에 있는 마크다운 제목들의 경로. 같거나 높은 단계의 제목이 나오면 앞 경로를 끊는다. */
    static String headingPath(List<String> lines, int before0) {
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (int i = 0; i < Math.min(before0, lines.size()); i++) {
            String line = lines.get(i);
            int level = 0;
            while (level < line.length() && level < 6 && line.charAt(level) == '#') level++;
            if (level == 0 || level >= line.length() || line.charAt(level) != ' ') continue;
            String title = line.substring(level).strip();
            if (title.isEmpty()) continue;
            while (!levels.isEmpty() && levels.getLast() >= level) {
                levels.removeLast();
                titles.removeLast();
            }
            levels.add(level);
            titles.add(title);
        }
        return String.join(" / ", titles);
    }
}
