package com.beez.backend.planning.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 긴 문서를 AI 에 부분별로 보내기 위해 줄 범위로 나눈다(S15P31A108-389). 모델을 부르지 않고 접수 때 한 번 계산한다.
 *
 * <p>줄 번호는 정규화된 본문 기준 1부터이며 양 끝을 포함한다(AI {@code scope} 와 같은 기준: 줄바꿈 수 + 1 이 전체 줄 수).
 * 가능하면 마크다운 제목 줄 바로 앞에서 끊고, 한 절이 너무 길면 빈 줄에서 끊는다. 빈 줄이 하나도 없는 긴 덩어리는 글자 수로 끊는다.
 * 부분이 너무 많아지면 부분 크기를 키워 {@code maxParts} 안에 맞춘다(접수를 거절하지 않는다).
 */
final class DraftPartPlanner {
    record Part(int start, int end) { }

    private DraftPartPlanner() { }

    static List<Part> plan(String content, int maxChars, int maxParts) {
        String[] lines = content.split("\n", -1);
        int limit = Math.max(1, maxChars);
        List<Part> parts = split(lines, limit);
        // 줄 단위 합계가 더 커질 일은 없으므로 부분 수가 넘치면 크기를 키워 다시 나눈다(최대 몇 번 안에 끝난다).
        for (int guard = 0; parts.size() > maxParts && guard < 16; guard++) {
            limit = Math.max(limit + 1, (int) Math.ceil(limit * (double) parts.size() / maxParts) + 1);
            parts = split(lines, limit);
        }
        return parts;
    }

    private static List<Part> split(String[] lines, int limit) {
        var parts = new ArrayList<Part>();
        int start = 0, size = 0, headCut = -1, blankCut = -1;   // 끊기 좋은 줄 번호(0부터): 이 줄 앞에서 끊는다
        for (int i = 0; i < lines.length; i++) {
            int len = lines[i].length() + 1;
            if (i > start && size + len > limit) {
                int cut = headCut > start ? headCut : blankCut > start ? blankCut : i;
                parts.add(new Part(start + 1, cut));
                start = cut; headCut = -1; blankCut = -1; size = 0;
                for (int j = start; j < i; j++) size += lines[j].length() + 1;
            }
            if (i > start && lines[i - 1].isBlank() && !lines[i].isBlank()) {
                blankCut = i;
                if (lines[i].startsWith("#")) headCut = i;
            }
            size += len;
        }
        parts.add(new Part(start + 1, lines.length));
        return parts;
    }
}
