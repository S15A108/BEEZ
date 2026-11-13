package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S15P31A108-414 known_questions 의 상한. AI 스키마(PlanningQuestionRequest)가 같은 값으로 요청 전체를 거절하므로,
 * 여기서 상한을 넘겨 보내면 질문 생성이 모두 실패한다. 글자 수는 AI 가 세는 단위(코드 포인트)로 센다.
 */
class KnownQuestionsTest {

    static Map<String, Object> row(String question, String status, String answer) {
        var row = new HashMap<String, Object>();
        row.put("question", question); row.put("status", status); row.put("answer", answer);
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

    @Test void stopsBeforeTheCharacterCapAndCutsLongQuestions() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) rows.add(row("가".repeat(2500), "ANSWERED", "나".repeat(300)));
        var out = PlanningQuestionGenerator.known(rows);
        int chars = out.stream().mapToInt(m -> ((String) m.get("question")).length() + ((String) m.get("answer")).length()).sum();
        assertThat(((String) out.get(0).get("question"))).hasSize(PlanningQuestionGenerator.KNOWN_QUESTION_CHARS);
        assertThat(chars).isLessThanOrEqualTo(PlanningQuestionGenerator.MAX_KNOWN_CHARS);
        assertThat(out).as("2,200자씩 13개 = 28,600자, 14번째는 상한을 넘는다").hasSize(13);
    }
}
