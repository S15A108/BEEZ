package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 133 계약 §6 답변 검증의 BE 쪽 규칙. */
class PlanningAnswersTest {
    static final Map<String, Object> CHOICE = Map.of("type", "CHOICE", "options", List.of("가", "나", "다"), "multiple", false, "allow_other", true);
    static final Map<String, Object> MULTI = Map.of("type", "CHOICE", "options", List.of("가", "나", "다"), "multiple", true, "allow_other", false);
    static final Map<String, Object> SHORT = Map.of("type", "SHORT_ANSWER", "max_length", 5);
    static final Map<String, Object> ESSAY = Map.of("type", "ESSAY", "max_length", 2000);


    @Test void essayAndShortTextBecomeStructuredPayloads() {
        var essay = PlanningAnswers.normalize("ESSAY", ESSAY, "팀장만 삭제한다.", null);
        assertThat(essay.display()).isEqualTo("팀장만 삭제한다.");
        assertThat(essay.payload()).isEqualTo(Map.of("type", "ESSAY", "text", "팀장만 삭제한다."));
        assertThat(PlanningAnswers.normalize("SHORT_ANSWER", SHORT, "12345", null).display()).isEqualTo("12345");
    }

    @Test void textLongerThanTheFormatAllowsIsRejectedByCodePoints() {
        assertThatThrownBy(() -> PlanningAnswers.normalize("SHORT_ANSWER", SHORT, "123456", null)).isInstanceOf(ApiException.class);
        assertThat(PlanningAnswers.normalize("SHORT_ANSWER", SHORT, "😀😀😀😀😀", null).display()).hasSize(10);
    }

    @Test void blankAnswersAreRejected() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> PlanningAnswers.normalize("ESSAY", ESSAY, blank, null)).isInstanceOf(ApiException.class);
        }
    }

    @Test void choiceStringMatchingAnOptionIsASelectionElseOtherText() {
        var selected = PlanningAnswers.normalize("CHOICE", CHOICE, "나", null);
        assertThat(selected.payload()).isEqualTo(Map.of("type", "CHOICE", "selected", List.of("나")));
        var other = PlanningAnswers.normalize("CHOICE", CHOICE, "라", null);
        assertThat(other.payload().get("selected")).isEqualTo(List.of());
        assertThat(other.payload().get("other_text")).isEqualTo("라");
        assertThat(other.display()).isEqualTo("라");
    }

    @Test void choiceWithoutOtherRejectsUnknownText() {
        assertThatThrownBy(() -> PlanningAnswers.normalize("CHOICE", MULTI, "라", null)).isInstanceOf(ApiException.class);
    }

    @Test void choicePayloadRules() {
        assertThat(PlanningAnswers.normalize("CHOICE", MULTI, null, Map.of("type", "CHOICE", "selected", List.of("가", "다"))).display()).isEqualTo("가, 다");
        // 하나만 고르는 질문에 둘, 중복, 없는 선택지, 빈 선택
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "CHOICE", "selected", List.of("가", "나")),
                Map.of("type", "CHOICE", "selected", List.of("가", "가")),
                Map.of("type", "CHOICE", "selected", List.of("라")),
                Map.of("type", "CHOICE", "selected", List.of()),
                Map.of("type", "ESSAY", "text", "다른 유형"))) {
            assertThatThrownBy(() -> PlanningAnswers.normalize("CHOICE", CHOICE, null, bad)).isInstanceOf(ApiException.class);
        }
        // 기타를 받지 않는 질문
        assertThatThrownBy(() -> PlanningAnswers.normalize("CHOICE", MULTI, null, Map.of("type", "CHOICE", "selected", List.of("가"), "other_text", "x")))
                .isInstanceOf(ApiException.class);
        // 선택과 기타를 함께
        assertThat(PlanningAnswers.normalize("CHOICE", CHOICE, null, Map.of("type", "CHOICE", "selected", List.of("가"), "other_text", "추가")).display())
                .isEqualTo("가, 추가");
    }

    @Test void legacyQuestionWithoutTypeKeepsFreeText() {
        var legacy = PlanningAnswers.normalize(null, Map.of(), "예전 질문의 답", null);
        assertThat(legacy.display()).isEqualTo("예전 질문의 답");
        assertThat(legacy.payload()).isNull();
    }
}
