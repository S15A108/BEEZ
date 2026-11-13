package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S15P31A108-414 설계 초안 입력 related_answers 의 상한. AI 스키마(DesignDraftRequest)가 같은 값으로 요청 전체를 거절하므로,
 * 여기서 넘겨 보내면 그 프로젝트의 초안 생성이 모두 실패한다.
 */
class RelatedAnswersTest {

    static Map<String, Object> row(long id, String question, String answer) {
        var row = new HashMap<String, Object>();
        row.put("id", id); row.put("question", question);
        row.put("answer_payload", "{\"type\":\"ESSAY\",\"text\":\"" + answer + "\"}");
        row.put("document_id", 7L); row.put("document_version_id", 70L); row.put("version_no", 2);
        return row;
    }

    @Test void keepsTheOriginalAnswerAndItsSourceUnderAPrefixedKey() {
        var out = DesignDraftService.related(List.of(row(5, "재취소는?", "409로 거부한다.")));
        assertThat(out).containsExactly(Map.of("ref_key", "pq-5", "question", "재취소는?",
                "answer", Map.of("type", "ESSAY", "text", "409로 거부한다."),
                "source", Map.of("document_id", "7", "document_version_id", "70", "version_no", 2)));
    }

    @Test void stopsAtTheCountAndCharacterCapsKeepingTheNewest() {
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 150; i++) many.add(row(1000 - i, "q" + i, "a"));
        var out = DesignDraftService.related(many);
        assertThat(out).hasSize(DesignDraftService.MAX_RELATED_ANSWERS);
        assertThat(out.get(0).get("ref_key")).isEqualTo("pq-1000");

        var long_ = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10; i++) long_.add(row(i, "가".repeat(2000), "나".repeat(4000)));
        assertThat(DesignDraftService.related(long_)).as("구조 답으로 센다: 6,000자씩 5개 = 30,000자, 6번째는 상한을 넘는다").hasSize(5);

        var choice = row(9, "누가 삭제하나요?", "x");
        choice.put("answer_payload", "{\"type\":\"CHOICE\",\"selected\":[\"팀장\",\"멤버\"],\"other_text\":\"운영자\"}");
        var filler = row(8, "가".repeat(2000), "나".repeat(27_990));
        assertThat(DesignDraftService.related(List.of(choice, filler))).as("선택지·기타 입력 합 7자 + 질문 9자 = 16자, 뒤는 30,006자라 멈춤").hasSize(1);
    }
}
