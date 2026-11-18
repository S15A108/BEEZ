package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DraftPartPlannerTest {
    static String section(int no, int lines) {
        var text = new StringBuilder("## 절 " + no + "\n\n");
        for (int i = 0; i < lines; i++) text.append("항목 ").append(no).append("-").append(i).append("은 이렇게 동작한다.\n\n");
        return text.toString();
    }

    @Test void shortDocumentIsOnePartCoveringAllLines() {
        var parts = DraftPartPlanner.plan("# 제목\n\n본문\n", 6000, 12);
        assertThat(parts).containsExactly(new DraftPartPlanner.Part(1, 4));
    }

    @Test void partsCoverEveryLineOnceWithoutGapsOrOverlap() {
        String content = IntStream.rangeClosed(1, 8).mapToObj(i -> section(i, 6)).collect(Collectors.joining());
        int total = content.split("\n", -1).length;
        var parts = DraftPartPlanner.plan(content, 500, 50);
        assertThat(parts.size()).isGreaterThan(1);
        assertThat(parts.get(0).start()).isEqualTo(1);
        assertThat(parts.get(parts.size() - 1).end()).isEqualTo(total);
        for (int i = 1; i < parts.size(); i++) assertThat(parts.get(i).start()).isEqualTo(parts.get(i - 1).end() + 1);
    }

    @Test void partsStartAtHeadingsWhenASectionFits() {
        String content = IntStream.rangeClosed(1, 6).mapToObj(i -> section(i, 3)).collect(Collectors.joining());
        String[] lines = content.split("\n", -1);
        var parts = DraftPartPlanner.plan(content, 400, 50);
        for (var part : parts.subList(1, parts.size())) assertThat(lines[part.start() - 1]).startsWith("## ");
    }

    @Test void tooManyPartsGrowThePartSizeInsteadOfFailing() {
        String content = IntStream.rangeClosed(1, 40).mapToObj(i -> section(i, 4)).collect(Collectors.joining());
        var parts = DraftPartPlanner.plan(content, 300, 5);
        assertThat(parts.size()).isLessThanOrEqualTo(5);
        assertThat(parts.get(parts.size() - 1).end()).isEqualTo(content.split("\n", -1).length);
    }

    @Test void aLongSectionWithoutBlankLinesStillSplitsByLine() {
        String content = IntStream.range(0, 100).mapToObj(i -> "줄 " + i + " 내용이 이어진다").collect(Collectors.joining("\n"));
        var parts = DraftPartPlanner.plan(content, 300, 50);
        assertThat(parts.size()).isGreaterThan(1);
    }
}
