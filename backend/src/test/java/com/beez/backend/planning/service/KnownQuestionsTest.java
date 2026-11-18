package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S15P31A108-414 known_questions 의 상한과 범위. AI 스키마(PlanningQuestionRequest)가 같은 값으로 요청 전체를 거절하므로,
 * 여기서 상한을 넘겨 보내면 질문 생성이 모두 실패한다. 글자 수는 AI 가 세는 단위(코드 포인트)로 센다.
 * 답한 질문은 설계 초안 related_answers 와 같은 규칙으로 고르고 세야, 모델이 다시 묻지 않은 결정이 초안에서 빠지지 않는다.
 */
class KnownQuestionsTest {

    static Map<String, Object> row(String question, String status, String answer) {
        var row = new HashMap<String, Object>();
        row.put("question", question); row.put("status", status); row.put("answer", answer);
        if ("ANSWERED".equals(status)) row.put("answer_payload", "{\"type\":\"ESSAY\",\"text\":\"" + answer + "\"}");
        return row;
    }

    @Test void answerIsSentOnlyForAnsweredQuestionsAndCutTo200CodePoints() {
        var out = PlanningQuestionGenerator.known(List.of(
                row("재취소는?", "ANSWERED", "😀".repeat(250)),
                row("겹침은?", "OPEN", null),
                row("보관은?", "DISMISSED", "닫기 전 남은 답"),
                row("빈 답은?", "ANSWERED", "  ")));
        assertThat(out).extracting(m -> m.get("status")).containsExactly("ANSWERED", "OPEN", "DISMISSED", "ANSWERED");
        String answer = (String) out.get(0).get("answer");
        assertThat(answer.codePointCount(0, answer.length())).as("서로게이트 쌍을 가르지 않고 200자").isEqualTo(200);
        assertThat(out.subList(1, 4)).extracting(m -> m.get("answer")).containsOnlyNulls();
    }

    @Test void keepsTheNewestWithinOneHundredQuestions() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 150; i++) rows.add(row("q" + i, "OPEN", null));
        var out = PlanningQuestionGenerator.known(rows);
        assertThat(out).hasSize(PlanningQuestionGenerator.MAX_KNOWN_QUESTIONS);
        assertThat(out.get(0).get("question")).as("입력이 최근 것부터이므로 앞쪽이 남는다").isEqualTo("q0");
    }

    @Test void cutsLongOpenQuestionsAndStopsBeforeTheCharacterCap() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) rows.add(row("가".repeat(2500), "OPEN", null));
        var out = PlanningQuestionGenerator.known(rows);
        assertThat(((String) out.get(0).get("question"))).hasSize(PlanningQuestionGenerator.KNOWN_QUESTION_CHARS);
        assertThat(out).as("2,000자씩 15개 = 30,000자, 16번째는 상한을 넘는다").hasSize(15);
    }

    // 답한 질문은 보내는 건 앞 200자지만 크기는 초안에 넘어갈 구조 답 전체로 센다 — 초안 쪽 상한 안에 반드시 들어가게.
    @Test void answeredQuestionsAreSizedLikeTheDraftsRelatedAnswersAndLeftOutWhenTheDraftCannotTakeThem() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10; i++) rows.add(row("가".repeat(2000), "ANSWERED", "나".repeat(4000)));
        var out = PlanningQuestionGenerator.known(rows);
        assertThat(out).as("6,000자씩 5개 = 30,000자 — 초안 related_answers 와 같은 개수").hasSize(5)
                .hasSize(DesignDraftService.related(relatedRows(rows)).size());
        assertThat(out).allSatisfy(m -> assertThat((String) m.get("answer")).hasSize(PlanningQuestionGenerator.KNOWN_ANSWER_CHARS));

        var legacy = row("예전 답", "ANSWERED", "답");
        legacy.remove("answer_payload");
        var tooLong = row("가".repeat(2001), "ANSWERED", "답");
        assertThat(PlanningQuestionGenerator.known(List.of(legacy, tooLong, row("겹침은?", "OPEN", null))))
                .as("초안에 넘길 수 없는 답(구조 답 없음·2,000자 넘는 질문)은 빼고, 미답변 질문은 그대로")
                .extracting(m -> m.get("question")).containsExactly("겹침은?");
    }

    static List<Map<String, Object>> relatedRows(List<Map<String, Object>> rows) {
        var out = new ArrayList<Map<String, Object>>();
        long id = 1;
        for (var r : rows) {
            var copy = new HashMap<>(r);
            copy.put("id", id++); copy.put("document_id", 1L); copy.put("document_version_id", 2L); copy.put("version_no", 1);
            out.add(copy);
        }
        return out;
    }
}
