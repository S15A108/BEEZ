package com.beez.backend.common.diff;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.common.api.ApiException;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test void identicalContentHasNoDifference() {
        assertThat(LineDiff.compare("안녕\n", "안녕\n")).isEmpty();
        assertThat(LineDiff.compare("", "")).isEmpty();
    }
    @Test void preservesContextAndShowsIndividualAddedAndRemovedLines() {
        assertThat(LineDiff.compare("첫째\n이전\n끝\n", "첫째\n수정\n추가\n끝\n"))
                .isEqualTo("--- base\n+++ proposed\n@@ -1,3 +1,4 @@\n 첫째\n-이전\n+수정\n+추가\n 끝\n");
    }
    @Test void distinguishesMissingFinalNewlineAndBlankLines() {
        assertThat(LineDiff.compare("a", "a\n")).contains("-a\n\\ No newline at end of file\n+a\n");
        assertThat(LineDiff.compare("", "\n")).contains("@@ -0,0 +1,1 @@\n+\n");
        assertThat(LineDiff.compare("a\r\n", "a\n")).contains("-a\r\n+a\n");
    }
    @Test void boundedFallbackStillContainsEveryChangedLine() {
        String before = "old\n".repeat(2000), after = "new\n".repeat(2000);
        String diff = LineDiff.compare(before, after);
        assertThat(diff.lines().filter(l -> l.equals("-old")).count()).isEqualTo(2000);
        assertThat(diff.lines().filter(l -> l.equals("+new")).count()).isEqualTo(2000);
    }
    @Test void rejectsOversizedInputInsteadOfSilentlyTruncating() {
        assertThatThrownBy(() -> LineDiff.compare("x".repeat(1_048_577), ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("비교할 본문");
        assertThatThrownBy(() -> LineDiff.compare("\n".repeat(50_001), ""))
                .isInstanceOf(ApiException.class);
    }
}
