package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.designitem.support.SourceLocators;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignDraftAdoptLocatorTest {
    /**
     * S15P31A108-422 회귀: 원문 인용 없는 후보(답변만 근거·근거 없음 확인)를 채택하면 출처 문서만 찍히고 위치가 비어, 그 항목을
     * 참조하는 작업 검증이 영구히 막혔다(dev 프로젝트 30 REQ-015·016). 인용 없음 표시가 남아야 근거 수집이 건너뛴다.
     */
    @Test void candidateWithoutQuotesIsAdoptedAsNoQuote() {
        assertThat(DesignDraftService.locator(Map.of("sources", "[]"))).isEqualTo(SourceLocators.NO_QUOTE);
        String quoted = DesignDraftService.locator(Map.of("sources",
                "[{\"locator\":{\"type\":\"text_lines\",\"start_line\":13,\"end_line\":13},\"location\":\"요구사항 / L13-L13\"}]"));
        assertThat(DesignJson.object(quoted)).containsEntry("start_line", 13).containsEntry("location", "요구사항 / L13-L13");
    }

    /** 리뷰 반영: 첫 인용에만 위치가 없을 때 위치를 비우면 같은 막힘이 생겼다. 위치가 있는 다음 인용을 쓴다. */
    @Test void firstQuoteWithALocationIsTheRepresentativeSource() {
        String picked = DesignDraftService.locator(Map.of("sources",
                "[{\"quote\":\"위치 없음\"},{\"locator\":{\"type\":\"text_lines\",\"start_line\":29,\"end_line\":29},"
                        + "\"location\":\"요구사항 / L29-L29\"}]"));
        assertThat(DesignJson.object(picked)).containsEntry("start_line", 29);
    }
}
