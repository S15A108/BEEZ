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
    void rangeJoinsWithLf() {
        List<String> lines = NormalizedText.lines("a\nb\nc\n");
        assertThat(NormalizedText.range(lines, 1, 1)).isEqualTo("a");
        assertThat(NormalizedText.range(lines, 2, 3)).isEqualTo("b\nc");
        assertThatThrownBy(() -> NormalizedText.range(lines, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalizedText.range(lines, 3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalizedText.range(lines, 2, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
