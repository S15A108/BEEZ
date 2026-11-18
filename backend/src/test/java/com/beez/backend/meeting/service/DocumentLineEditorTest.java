package com.beez.backend.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.meeting.service.DocumentLineEditor.Conflict;
import com.beez.backend.meeting.service.DocumentLineEditor.LineChange;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** S15P31A108-297 정리의 줄 변경을 원문에 적용하는 순수 함수. before 는 각 줄의 LF 를 포함한다(NormalizedText.range 기준). */
@Tag("unit")
class DocumentLineEditorTest {
    static LineChange change(int start, int end, String before, String after) {
        return new LineChange(start, end, before, after);
    }

    static String apply(String text, LineChange... changes) {
        return DocumentLineEditor.apply(text, List.of(changes));
    }

    @Test void replacesTheFirstLine() {
        assertThat(apply("a\nb\nc\n", change(1, 1, "a\n", "A\n"))).isEqualTo("A\nb\nc\n");
    }

    @Test void replacesTheLastLineOfADocumentThatEndsWithLf() {
        assertThat(apply("a\nb\nc\n", change(3, 3, "c\n", "C\n"))).isEqualTo("a\nb\nC\n");
    }

    @Test void keepsALineBreakEvenWhenAfterOmitsIt() {
        // after 에 LF 가 없어도 뒤의 줄과 붙지 않는다
        assertThat(apply("a\nb\nc\n", change(2, 2, "b\n", "B"))).isEqualTo("a\nB\nc\n");
    }

    @Test void keepsTheMissingFinalLineBreak() {
        // 문서가 LF 로 끝나지 않으면 마지막 줄의 before 도 LF 가 없고 결과도 LF 없이 끝난다
        assertThat(apply("a\nb", change(2, 2, "b", "B"))).isEqualTo("a\nB");
        assertThat(apply("a\nb", change(2, 2, "b", "B\n"))).isEqualTo("a\nB");
    }

    @Test void addsTheFinalLineBreakOnlyWhenTheOriginalHadOne() {
        assertThat(apply("a\nb\n", change(2, 2, "b\n", "B"))).isEqualTo("a\nB\n");
    }

    @Test void turnsOneLineIntoManyAndManyIntoOne() {
        assertThat(apply("a\nb\nc\n", change(2, 2, "b\n", "b1\nb2\nb3\n"))).isEqualTo("a\nb1\nb2\nb3\nc\n");
        assertThat(apply("a\nb\nc\nd\n", change(2, 3, "b\nc\n", "bc\n"))).isEqualTo("a\nbc\nd\n");
    }

    @Test void emptyAfterDeletesTheLines() {
        assertThat(apply("a\nb\nc\n", change(2, 2, "b\n", ""))).isEqualTo("a\nc\n");
        assertThat(apply("a\nb\nc\n", change(1, 3, "a\nb\nc\n", ""))).isEmpty();
    }

    @Test void deletingTheLastLineOfADocumentWithoutFinalLfDropsTheDanglingLineBreak() {
        assertThat(apply("a\nb", change(2, 2, "b", ""))).isEqualTo("a");
    }

    @Test void appliesSeveralChangesFromTheBackWithoutShiftingLineNumbers() {
        // 줄 수가 바뀌는 변경이 앞에 있어도 뒤 변경의 줄 번호는 원문 기준이다. 입력 순서와 상관없다
        String result = apply("a\nb\nc\nd\ne\n",
                change(4, 4, "d\n", "D\n"),
                change(1, 2, "a\nb\n", "x\ny\nz\n"));
        assertThat(result).isEqualTo("x\ny\nz\nc\nD\ne\n");
    }

    @Test void countsLinesOnNormalizedText() {
        // CRLF·BOM 은 정규화한 뒤 센다. 결과도 LF 이다
        assertThat(apply("﻿a\r\nb\r\nc\r\n", change(2, 2, "b\n", "B\n"))).isEqualTo("a\nB\nc\n");
        assertThat(apply("a\nb\n", change(1, 1, "a\n", "A\r\n"))).isEqualTo("A\nb\n");
    }

    @Test void rejectsRangesOutsideTheText() {
        assertThatThrownBy(() -> apply("a\nb\n", change(3, 3, "c\n", "C\n"))).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> apply("a\nb\n", change(2, 3, "b\n", "B\n"))).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> apply("a\nb\n", change(0, 1, "a\n", "A\n"))).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> apply("", change(1, 1, "", "A\n"))).isInstanceOf(Conflict.class);
    }

    @Test void rejectsOverlappingChanges() {
        assertThatThrownBy(() -> apply("a\nb\nc\n", change(1, 2, "a\nb\n", "X\n"), change(2, 3, "b\nc\n", "Y\n")))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> apply("a\nb\nc\n", change(2, 2, "b\n", "X\n"), change(2, 2, "b\n", "Y\n")))
                .isInstanceOf(Conflict.class);
    }

    @Test void rejectsWhenBeforeNoLongerMatches() {
        assertThatThrownBy(() -> apply("a\nb\n", change(1, 1, "z\n", "A\n"))).isInstanceOf(Conflict.class);
        // 원문 끝 줄바꿈 유무가 다르면 before 도 다르다
        assertThatThrownBy(() -> apply("a\nb", change(2, 2, "b\n", "B"))).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> apply("a\nb\n", change(1, 1, null, "A\n"))).isInstanceOf(Conflict.class);
    }

    @Test void noChangesReturnsTheNormalizedText() {
        assertThat(apply("a\r\nb\r\n")).isEqualTo("a\nb\n");
        assertThat(apply("a\nb")).isEqualTo("a\nb");
    }
}
