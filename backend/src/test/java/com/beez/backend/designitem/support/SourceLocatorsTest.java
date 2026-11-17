package com.beez.backend.designitem.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceLocatorsTest {
    private static final String DOC = "# 테트리스\n## 1. 기능\n### 1.2 방\n준비 취소\n## 2. 비기능\n지연 100ms\n";

    /**
     * 팀장이 지정한 위치는 AI 가 만든 위치와 같은 "제목 경로 / Ls-Le" 로 보여야 하고(-422), 근거 수집이 읽는 줄 범위를 담아야 한다.
     * 같은 단계 제목이 나오면 앞 경로를 끊지 않으면 엉뚱한 절이 붙는다.
     */
    @Test void linesCarryTheRangeAndTheHeadingPathAboveIt() {
        var locator = DesignJson.object(SourceLocators.lines(DOC, 4, 4));
        assertThat(locator).containsEntry("type", "text_lines").containsEntry("start_line", 4).containsEntry("end_line", 4)
                .containsEntry("location", "테트리스 / 1. 기능 / 1.2 방 / L4-L4");
        assertThat(DesignJson.object(SourceLocators.lines(DOC, 6, 6))).containsEntry("location", "테트리스 / 2. 비기능 / L6-L6");
        assertThat(DesignJson.object(SourceLocators.lines("본문만\n", 1, 1))).containsEntry("location", "L1-L1");
    }

    /** 문서 밖 범위나 근거 수집 상한을 넘는 발췌는 저장해도 검증이 다시 막히므로 받지 않는다. */
    @Test void rangesOutsideTheDocumentOrTooLargeForEvidenceAreRefused() {
        assertThat(SourceLocators.lines(DOC, 6, 7)).isNull();
        assertThat(SourceLocators.lines(DOC, 0, 1)).isNull();
        assertThat(SourceLocators.lines("가".repeat(SourceLocators.MAX_EXCERPT_BYTES) + "\n", 1, 1)).isNull();
    }
}
