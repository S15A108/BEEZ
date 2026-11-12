package com.beez.backend.designitem.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.designitem.support.SourceRemapper.NeedsConfirmation;
import com.beez.backend.designitem.support.SourceRemapper.Remapped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourceRemapperTest {

    private static String doc(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static Map<String, Object> lines(int start, int end, String location) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("type", "text_lines");
        locator.put("start_line", start);
        locator.put("end_line", end);
        if (location != null) locator.put("location", location);
        return locator;
    }

    private static Remapped moved(SourceRemapper.Result result) {
        assertThat(result).isInstanceOf(Remapped.class);
        return (Remapped) result;
    }

    private static void needsConfirmation(SourceRemapper.Result result, String reason) {
        assertThat(result).isInstanceOf(NeedsConfirmation.class);
        assertThat(((NeedsConfirmation) result).reason()).isEqualTo(reason);
    }

    @Test
    void unchangedDocumentKeepsTheSameLines() {
        String text = doc("a", "b", "c", "d");
        var result = moved(SourceRemapper.of(text, text).remap(lines(2, 3, "문서 / L2-L3")));
        assertThat(result.locator()).containsEntry("start_line", 2).containsEntry("end_line", 3)
                .containsEntry("location", "문서 / L2-L3").containsEntry("type", "text_lines");
    }

    @Test
    void linesInsertedAboveShiftTheRangeAndRebuildLocation() {
        String old = doc("# 명세", "a", "b", "c");
        String added = doc("# 명세", "새 줄 1", "새 줄 2", "a", "b", "c");
        var result = moved(SourceRemapper.of(old, added).remap(lines(3, 4, "명세 / L3-L4")));
        assertThat(result.locator()).containsEntry("start_line", 5).containsEntry("end_line", 6)
                .containsEntry("location", "명세 / L5-L6");
    }

    @Test
    void linesDeletedAboveShiftTheRangeUp() {
        String old = doc("a", "b", "c", "d", "e");
        String removed = doc("c", "d", "e");
        var result = moved(SourceRemapper.of(old, removed).remap(lines(4, 5, "L4-L5")));
        assertThat(result.locator()).containsEntry("start_line", 2).containsEntry("end_line", 3)
                .containsEntry("location", "L2-L3");
    }

    @Test
    void editBelowTheRangeDoesNotMoveIt() {
        String old = doc("a", "b", "c", "d");
        String edited = doc("a", "b", "c", "다른 d");
        var result = moved(SourceRemapper.of(old, edited).remap(lines(1, 2, "L1-L2")));
        assertThat(result.locator()).containsEntry("start_line", 1).containsEntry("end_line", 2);
    }

    @Test
    void modifiedLineInsideTheRangeNeedsConfirmation() {
        String old = doc("a", "b", "c", "d");
        String edited = doc("a", "b 수정", "c", "d");
        needsConfirmation(SourceRemapper.of(old, edited).remap(lines(2, 3, "L2-L3")), "passage_changed");
    }

    @Test
    void deletedLineInsideTheRangeNeedsConfirmation() {
        String old = doc("a", "b", "c", "d");
        String edited = doc("a", "c", "d");
        needsConfirmation(SourceRemapper.of(old, edited).remap(lines(2, 3, "L2-L3")), "passage_changed");
    }

    @Test
    void lineInsertedInsideTheRangeNeedsConfirmation() {
        String old = doc("a", "b", "c", "d");
        String edited = doc("a", "b", "끼운 줄", "c", "d");
        needsConfirmation(SourceRemapper.of(old, edited).remap(lines(2, 3, "L2-L3")), "passage_changed");
    }

    @Test
    void repeatedBlockWithoutAnchorIsAmbiguous() {
        // 같은 한 줄이 두 번 있고 이웃 줄이 모두 바뀌어, 어느 쪽 줄을 가리키는지 증명할 수 없다.
        String old = doc("앞1", "공통", "뒤1");
        String edited = doc("앞2", "공통", "뒤2", "공통", "뒤3");
        needsConfirmation(SourceRemapper.of(old, edited).remap(lines(2, 2, "L2-L2")), "ambiguous");
    }

    @Test
    void repeatedBlockWithAnAnchoredNeighborIsMoved() {
        String old = doc("앞", "공통", "뒤");
        String edited = doc("새로 추가", "앞", "공통", "뒤", "공통", "다른 뒤");
        var result = moved(SourceRemapper.of(old, edited).remap(lines(2, 2, "L2-L2")));
        assertThat(result.locator()).containsEntry("start_line", 3).containsEntry("end_line", 3);
    }

    @Test
    void firstAndLastLinesAreSupported() {
        String old = doc("a", "b", "c");
        String added = doc("0", "a", "b", "c", "d");
        assertThat(moved(SourceRemapper.of(old, added).remap(lines(1, 1, "L1-L1"))).locator())
                .containsEntry("start_line", 2);
        assertThat(moved(SourceRemapper.of(old, added).remap(lines(3, 3, "L3-L3"))).locator())
                .containsEntry("start_line", 4);
    }

    @Test
    void lastLineWithoutTrailingNewlineIsSupported() {
        String old = "a\nb\nc";
        String added = "0\na\nb\nc";
        var result = moved(SourceRemapper.of(old, added).remap(lines(3, 3, "L3-L3")));
        assertThat(result.locator()).containsEntry("start_line", 4).containsEntry("end_line", 4);
    }

    @Test
    void rangeBeyondTheOldDocumentNeedsConfirmation() {
        String text = doc("a", "b");
        needsConfirmation(SourceRemapper.of(text, text).remap(lines(2, 5, "L2-L5")), "range_outside_document");
    }

    @Test
    void headingBelongingChangeNeedsConfirmation() {
        String old = doc("# 1. 로그인", "a", "b");
        String renamed = doc("# 1. 인증", "a", "b");
        needsConfirmation(SourceRemapper.of(old, renamed).remap(lines(2, 3, "명세 / 1. 로그인 / L2-L3")), "heading_changed");
    }

    @Test
    void sameHeadingKeepsTheHeadingPath() {
        String old = doc("# 1. 로그인", "a", "b");
        String added = doc("# 0. 개요", "소개", "# 1. 로그인", "a", "b");
        var result = moved(SourceRemapper.of(old, added).remap(lines(2, 3, "명세 / 1. 로그인 / L2-L3")));
        assertThat(result.locator()).containsEntry("location", "명세 / 1. 로그인 / L4-L5");
    }

    @Test
    void legacyDisplayLocationWithoutLineNumbersIsRebuilt() {
        String old = doc("a", "b", "c");
        String added = doc("0", "a", "b", "c");
        Map<String, Object> locator = new LinkedHashMap<>(Map.of("location", "2~3행"));
        var result = moved(SourceRemapper.of(old, added).remap(locator));
        assertThat(result.locator()).containsEntry("location", "3~4행").doesNotContainKey("start_line");
        Map<String, Object> single = new LinkedHashMap<>(Map.of("location", "3행"));
        assertThat(moved(SourceRemapper.of(old, added).remap(single)).locator()).containsEntry("location", "4행");
    }

    @Test
    void locatorsWithoutARangeNeedConfirmation() {
        String text = doc("a", "b");
        var remapper = SourceRemapper.of(text, text);
        needsConfirmation(remapper.remap(new LinkedHashMap<>(Map.of("location", "2. REST API 목록"))), "legacy_locator");
        needsConfirmation(remapper.remap(new LinkedHashMap<>(Map.of("start_line", 1))), "legacy_locator");
        needsConfirmation(remapper.remap(new LinkedHashMap<>(Map.of("type", "heading", "start_line", 1, "end_line", 1))),
                "legacy_locator");
        needsConfirmation(remapper.remap(null), "legacy_locator");
    }

    @Test
    void locationWithAnUnknownShapeAndOldLineNumbersNeedsConfirmation() {
        String text = doc("a", "b", "c");
        needsConfirmation(SourceRemapper.of(text, text).remap(lines(2, 2, "2번째 줄")), "location_unrecognized");
        var untouched = moved(SourceRemapper.of(text, text).remap(lines(2, 2, "원문 전체")));
        assertThat(untouched.locator()).containsEntry("location", "원문 전체");
    }

    @Test
    void unreadableContentNeedsConfirmation() {
        needsConfirmation(SourceRemapper.of("a\r\nb\n", "a\nb\n").remap(lines(1, 1, "L1-L1")), "content_unavailable");
        needsConfirmation(SourceRemapper.of("﻿a\n", "a\n").remap(lines(1, 1, "L1-L1")), "content_unavailable");
        needsConfirmation(SourceRemapper.of(null, "a\n").remap(lines(1, 1, "L1-L1")), "content_unavailable");
        needsConfirmation(SourceRemapper.of("a\n", "").remap(lines(1, 1, "L1-L1")), "content_unavailable");
    }

    @Test
    void oversizedDocumentsNeedConfirmationInsteadOfRunningTheDiff() {
        String big = IntStream.rangeClosed(1, SourceRemapper.MAX_LINES + 1).mapToObj(i -> "줄" + i)
                .collect(Collectors.joining("\n")) + "\n";
        needsConfirmation(SourceRemapper.of(big, big).remap(lines(1, 1, "L1-L1")), "document_too_large");
    }

    @Test
    void completelyDifferentLargeDocumentsExceedTheEditDistanceBudget() {
        String left = IntStream.range(0, 1200).mapToObj(i -> "왼쪽" + i).collect(Collectors.joining("\n")) + "\n";
        String right = IntStream.range(0, 1200).mapToObj(i -> "오른쪽" + i).collect(Collectors.joining("\n")) + "\n";
        needsConfirmation(SourceRemapper.of(left, right).remap(lines(1, 1, "L1-L1")), "diff_too_large");
    }

    @Test
    void largeDocumentWithSmallEditsIsMovedQuickly() {
        String old = IntStream.range(0, 10_000).mapToObj(i -> "줄" + i).collect(Collectors.joining("\n")) + "\n";
        String added = "새 첫 줄\n" + old.replace("줄5000\n", "줄5000 수정\n");
        var remapper = SourceRemapper.of(old, added);
        assertThat(moved(remapper.remap(lines(9001, 9002, "L9001-L9002"))).locator())
                .containsEntry("start_line", 9002).containsEntry("end_line", 9003);
        needsConfirmation(remapper.remap(lines(5001, 5001, "L5001-L5001")), "passage_changed");
    }

    @Test
    void locatorOtherKeysArePreserved() {
        String old = doc("a", "b");
        String added = doc("0", "a", "b");
        Map<String, Object> locator = lines(1, 2, "L1-L2");
        locator.put("note", "보존");
        assertThat(moved(SourceRemapper.of(old, added).remap(locator)).locator()).containsEntry("note", "보존");
    }
}
