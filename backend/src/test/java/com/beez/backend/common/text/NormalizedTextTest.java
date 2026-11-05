package com.beez.backend.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizedTextTest {

    @Test
    void trailingNewlineDoesNotAddALine() {
        assertThat(NormalizedText.lines("a\nb\n")).containsExactly("a", "b");
        assertThat(NormalizedText.lines("a\nb")).containsExactly("a", "b");
    }

    @Test
    void bomAndCarriageReturnsAreNormalized() {
        assertThat(NormalizedText.lines("﻿a\r\nb\rc\n")).containsExactly("a", "b", "c");
        assertThat(NormalizedText.normalize("﻿a\r\nb\rc")).isEqualTo("a\nb\nc");
    }

    @Test
    void emptyAndBlankDocuments() {
        assertThat(NormalizedText.lines("")).isEmpty();
        assertThat(NormalizedText.lines(null)).isEmpty();
        assertThat(NormalizedText.lines("\n")).containsExactly("");
        assertThat(NormalizedText.lines("a\n\nb\n")).containsExactly("a", "", "b");
    }

    @Test
    void rangeIncludesTheLfOfEveryLine() {
        List<String> lines = NormalizedText.lines("a\nb\nc\n");
        assertThat(NormalizedText.range(lines, true, 1, 1)).isEqualTo("a\n");
        assertThat(NormalizedText.range(lines, true, 2, 3)).isEqualTo("b\nc\n");
        assertThatThrownBy(() -> NormalizedText.range(lines, true, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalizedText.range(lines, true, 3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalizedText.range(lines, true, 2, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLastLineHasNoLfWhenTheDocumentDoesNotEndWithOne() {
        String text = "a\nb\nc";
        List<String> lines = NormalizedText.lines(text);
        assertThat(NormalizedText.endsWithLf(text)).isFalse();
        assertThat(NormalizedText.range(lines, false, 2, 3)).isEqualTo("b\nc");
        assertThat(NormalizedText.range(lines, false, 1, 2)).isEqualTo("a\nb\n");
        assertThat(NormalizedText.endsWithLf("a\r\n")).isTrue();
    }
}
