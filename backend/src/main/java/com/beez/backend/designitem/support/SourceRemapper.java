package com.beez.backend.designitem.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서가 바뀐 뒤 설계 항목이 인용한 줄 위치를 새 문서 버전으로 옮긴다(S15P31A108-406).
 *
 * <p>옛·새 문서 본문의 줄 단위 diff 로, 인용 범위의 모든 줄이 글자 그대로 새 문서에 있고 그 줄들이 새 문서에서 연속일 때만 옮긴다.
 * 줄이 수정·삭제·삽입됐거나 같은 내용이 여러 곳이라 위치를 증명할 수 없으면 옮기지 않고 {@link NeedsConfirmation} 을 돌려준다.
 * 상태가 없고 Spring·DB 에 의존하지 않는다 — Flyway 백필 마이그레이션도 이 클래스를 그대로 쓰므로 공개 시그니처를 바꾸면
 * 마이그레이션 시험이 깨지게 해 둔다.
 */
public final class SourceRemapper {

    /** 줄 수 상한. 넘으면 diff 비용을 발행 트랜잭션에 지우지 않고 확인 필요로 둔다. */
    public static final int MAX_LINES = 20_000;
    /** diff 가 허용하는 편집 거리 상한(메모리 O(D²)). 넘으면 확인 필요. */
    static final int MAX_EDIT_DISTANCE = 1_000;

    private static final Pattern DISPLAY_LINES = Pattern.compile("^([1-9][0-9]*)\\s*(?:~\\s*([1-9][0-9]*))?\\s*행$");
    private static final Pattern TRAILING_SPAN = Pattern.compile("^(.*?)L([1-9][0-9]*)-L([1-9][0-9]*)$", Pattern.DOTALL);

    public sealed interface Result permits Remapped, NeedsConfirmation { }

    /** 새 문서에서 같은 줄을 찾았다. locator 는 옛 값에서 줄 번호와 location 표시 문자열만 바꾼 것이다. */
    public record Remapped(Map<String, Object> locator) implements Result { }

    /** 옮길 수 없다. reason 은 로그·시험용 짧은 코드다. */
    public record NeedsConfirmation(String reason) implements Result { }

    private final List<String> oldLines;
    private final List<String> newLines;
    /** 옛 줄 인덱스 → 새 줄 인덱스(없으면 -1). null 이면 diff 를 만들 수 없는 경우 */
    private final int[] map;
    private final String unavailableReason;

    private SourceRemapper(List<String> oldLines, List<String> newLines, int[] map, String unavailableReason) {
        this.oldLines = oldLines;
        this.newLines = newLines;
        this.map = map;
        this.unavailableReason = unavailableReason;
    }

    /** 옛·새 본문의 줄 대응을 한 번 계산한다. 같은 문서 쌍의 여러 항목이 이 객체를 같이 쓴다. */
    public static SourceRemapper of(String oldContent, String newContent) {
        if (!usable(oldContent) || !usable(newContent)) return unavailable("content_unavailable");
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) return unavailable("document_too_large");
        int[] map = LineDiff.match(oldLines, newLines);
        if (map == null) return unavailable("diff_too_large");
        return new SourceRemapper(oldLines, newLines, map, null);
    }

    private static SourceRemapper unavailable(String reason) {
        return new SourceRemapper(List.of(), List.of(), null, reason);
    }

    /** 옛 locator(JSON 을 읽은 맵)가 가리키는 줄을 새 문서에서 찾는다. */
    public Result remap(Map<String, Object> locator) {
        if (map == null) return new NeedsConfirmation(unavailableReason);
        if (locator == null) return new NeedsConfirmation("legacy_locator");
        int[] range = range(locator);
        if (range == null) return new NeedsConfirmation("legacy_locator");
        int first = range[0], last = range[1];
        int oldCount = oldLines.size(), newCount = newLines.size();
        if (first < 1 || last < first || last > oldCount) return new NeedsConfirmation("range_outside_document");
        int length = last - first + 1;
        int start0 = first - 1;
        int newStart0 = map[start0];
        if (newStart0 < 0) return new NeedsConfirmation("passage_changed");
        for (int i = 0; i < length; i++) {
            if (map[start0 + i] != newStart0 + i) return new NeedsConfirmation("passage_changed");
        }
        // 방어: diff 결과와 무관하게 옮긴 줄이 글자 그대로 같은지 다시 확인한다.
        for (int i = 0; i < length; i++) {
            if (newStart0 + i >= newCount || !oldLines.get(start0 + i).equals(newLines.get(newStart0 + i))) {
                return new NeedsConfirmation("passage_changed");
            }
        }
        // 같은 줄 묶음이 새 문서에 여러 번 있으면 위치가 모호하다 — 앞이나 뒤의 이웃 줄이 함께 대응해 자리가 증명될 때만 옮긴다.
        if (occurrences(newLines, oldLines.subList(start0, start0 + length)) > 1) {
            boolean anchoredBefore = (start0 == 0 && newStart0 == 0)
                    || (start0 > 0 && newStart0 > 0 && map[start0 - 1] == newStart0 - 1);
            boolean anchoredAfter = (start0 + length == oldCount && newStart0 + length == newCount)
                    || (start0 + length < oldCount && map[start0 + length] == newStart0 + length);
            if (!anchoredBefore && !anchoredAfter) return new NeedsConfirmation("ambiguous");
        }
        // 제목 아래로 옮겼는데 소속 절이 달라졌다면 location 의 제목 경로를 믿을 수 없다.
        if (!nearestHeading(oldLines, start0).equals(nearestHeading(newLines, newStart0))) {
            return new NeedsConfirmation("heading_changed");
        }
        int newFirst = newStart0 + 1, newLast = newStart0 + length;
        Map<String, Object> moved = new LinkedHashMap<>(locator);
        boolean hasLines = locator.containsKey("start_line") && locator.containsKey("end_line");
        if (hasLines) {
            moved.put("start_line", newFirst);
            moved.put("end_line", newLast);
        }
        Object location = locator.get("location");
        if (location instanceof String text) {
            String rebuilt = location(text, first, last, newFirst, newLast);
            if (rebuilt == null) return new NeedsConfirmation("location_unrecognized");
            moved.put("location", rebuilt);
        } else if (!hasLines) {
            return new NeedsConfirmation("legacy_locator");
        }
        return new Remapped(moved);
    }

    /** locator 의 1-기반 포함 줄 범위. start_line/end_line 이 없으면 "N행"·"N~M행" 표시에서 읽는다. */
    static int[] range(Map<String, Object> locator) {
        Object start = locator.get("start_line"), end = locator.get("end_line");
        boolean hasStart = locator.containsKey("start_line"), hasEnd = locator.containsKey("end_line");
        Object type = locator.get("type");
        if (hasStart || hasEnd || locator.containsKey("type")) {
            if (!hasStart || !hasEnd || (type != null && !"text_lines".equals(type))) return null;
            if (!(start instanceof Number a) || !(end instanceof Number b) || a.doubleValue() != a.longValue()
                    || b.doubleValue() != b.longValue() || a.longValue() < 1 || b.longValue() < a.longValue()
                    || b.longValue() > Integer.MAX_VALUE) return null;
            return new int[] { a.intValue(), b.intValue() };
        }
        if (!(locator.get("location") instanceof String text)) return null;
        Matcher matcher = DISPLAY_LINES.matcher(text.strip());
        if (!matcher.matches()) return null;
        try {
            int first = Integer.parseInt(matcher.group(1));
            int last = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
            return last >= first ? new int[] { first, last } : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 표시 문자열의 줄 번호를 새 번호로 바꾼다. 알려진 형태는 "… / L29-L29"(끝의 L범위)와 "29행"·"29~31행".
     * 줄 번호가 없는 문자열은 그대로 두고, 알려진 형태가 아닌데 옛 줄 번호가 들어 있으면 null(옮길 수 없음).
     */
    static String location(String text, int first, int last, int newFirst, int newLast) {
        String stripped = text.strip();
        Matcher span = TRAILING_SPAN.matcher(stripped);
        if (span.matches()) {
            if (Integer.parseInt(span.group(2)) != first || Integer.parseInt(span.group(3)) != last) return null;
            return span.group(1) + "L" + newFirst + "-L" + newLast;
        }
        Matcher display = DISPLAY_LINES.matcher(stripped);
        if (display.matches()) {
            return newFirst == newLast && display.group(2) == null ? newFirst + "행"
                    : newFirst + "~" + newLast + "행";
        }
        for (Matcher number = Pattern.compile("(?<![0-9])([0-9]+)(?![0-9])").matcher(stripped); number.find(); ) {
            long value = Long.parseLong(number.group(1).length() > 9 ? "0" : number.group(1));
            if (value == first || value == last) return null;
        }
        return text;
    }

    private static boolean usable(String content) {
        return content != null && !content.isEmpty() && !content.startsWith("﻿") && content.indexOf('\r') < 0;
    }

    /** ValidationEvidenceBuilder.excerpt 와 같은 줄 규칙 — LF 로 나누고 마지막에 LF 가 있어도 빈 줄을 만들지 않는다. */
    static List<String> lines(String content) {
        List<String> lines = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int lf = content.indexOf('\n', pos);
            int next = lf < 0 ? content.length() : lf;
            lines.add(content.substring(pos, next));
            pos = lf < 0 ? content.length() : lf + 1;
        }
        return lines;
    }

    private static int occurrences(List<String> haystack, List<String> block) {
        int count = 0;
        for (int i = 0; i + block.size() <= haystack.size(); i++) {
            if (haystack.subList(i, i + block.size()).equals(block)) count++;
        }
        return count;
    }

    /** 범위 바로 앞의 가장 가까운 마크다운 제목 줄. 없으면 빈 문자열. */
    private static String nearestHeading(List<String> lines, int before0) {
        for (int i = Math.min(before0, lines.size()) - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.startsWith("#")) return line.strip();
        }
        return "";
    }

    /** Myers O(ND) 줄 diff. 같은 줄의 대응(옛 인덱스 → 새 인덱스)만 돌려준다. */
    static final class LineDiff {
        private LineDiff() { }

        /** 대응을 만들 수 없을 만큼 다르면(편집 거리 상한 초과) null */
        static int[] match(List<String> a, List<String> b) {
            int n = a.size(), m = b.size();
            int[] result = new int[n];
            java.util.Arrays.fill(result, -1);
            int prefix = 0;
            while (prefix < n && prefix < m && a.get(prefix).equals(b.get(prefix))) {
                result[prefix] = prefix;
                prefix++;
            }
            int suffix = 0;
            while (suffix < n - prefix && suffix < m - prefix && a.get(n - 1 - suffix).equals(b.get(m - 1 - suffix))) {
                result[n - 1 - suffix] = m - 1 - suffix;
                suffix++;
            }
            int[] x = intern(a.subList(prefix, n - suffix), b.subList(prefix, m - suffix), true);
            int[] y = intern(a.subList(prefix, n - suffix), b.subList(prefix, m - suffix), false);
            if (x.length == 0 || y.length == 0) return result;
            int[] middle = myers(x, y);
            if (middle == null) return null;
            for (int i = 0; i < middle.length; i++) {
                if (middle[i] >= 0) result[prefix + i] = prefix + middle[i];
            }
            return result;
        }

        /** 문자열을 같은 정수 번호로 바꿔 비교 비용을 줄인다. */
        private static int[] intern(List<String> a, List<String> b, boolean first) {
            Map<String, Integer> ids = new java.util.HashMap<>();
            for (String line : a) ids.putIfAbsent(line, ids.size());
            for (String line : b) ids.putIfAbsent(line, ids.size());
            List<String> side = first ? a : b;
            int[] out = new int[side.size()];
            for (int i = 0; i < out.length; i++) out[i] = ids.get(side.get(i));
            return out;
        }

        private static int[] myers(int[] a, int[] b) {
            int n = a.length, m = b.length;
            int limit = Math.min(n + m, MAX_EDIT_DISTANCE);
            int offset = limit + 1;
            int[] v = new int[2 * limit + 3];
            List<int[]> trace = new ArrayList<>();
            for (int d = 0; d <= limit; d++) {
                trace.add(v.clone());
                for (int k = -d; k <= d; k += 2) {
                    int x;
                    if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) x = v[offset + k + 1];
                    else x = v[offset + k - 1] + 1;
                    int y = x - k;
                    while (x < n && y < m && a[x] == b[y]) { x++; y++; }
                    v[offset + k] = x;
                    if (x >= n && y >= m) return backtrack(trace, a.length, b.length, d, offset, a, b);
                }
            }
            return null;
        }

        private static int[] backtrack(List<int[]> trace, int n, int m, int dEnd, int offset, int[] a, int[] b) {
            int[] result = new int[n];
            java.util.Arrays.fill(result, -1);
            int x = n, y = m;
            for (int d = dEnd; d >= 0; d--) {
                int[] v = trace.get(d);
                int k = x - y;
                int prevK = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) ? k + 1 : k - 1;
                int prevX = v[offset + prevK];
                int prevY = prevX - prevK;
                while (x > prevX && y > prevY) {
                    x--;
                    y--;
                    result[x] = y;
                }
                if (d > 0) { x = prevX; y = prevY; }
            }
            return result;
        }
    }
}
