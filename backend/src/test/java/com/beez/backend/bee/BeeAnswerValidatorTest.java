package com.beez.backend.bee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.bee.service.BeeAnswerValidator;
import com.beez.backend.bee.service.BeeAnswerValidator.Rejected;
import com.beez.backend.bee.service.BeeAnswerValidator.Sent;
import com.beez.backend.bee.service.BeeAnswerValidator.SentEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** BEE-A01 AI 응답을 보낸 집합과 대조하는 규칙 (S15P31A108-344). DB·Spring 없이 돈다 */
class BeeAnswerValidatorTest {

    static final Map<String, Object> LOCATOR = Map.of("type", "text_lines", "start_line", 3, "end_line", 4);

    static final Sent SENT = new Sent("401", "profile-1", Set.of("312", "313"),
            Map.of("ev-1", new SentEvidence("302", "312", 1, "chk-1", LOCATOR, "예약 명세")),
            Map.of("1104", "1004"), Set.of("501"));

    /** 보낸 집합과 정확히 맞는 정상 응답. 시험마다 한 군데씩 어긋나게 고친다 */
    static Map<String, Object> response(Consumer<Map<String, Object>> mutate) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("n", 1);
        citation.put("evidence_id", "ev-1");
        citation.put("document_id", "302");
        citation.put("document_version_id", "312");
        citation.put("version_no", 1);
        citation.put("chunk_key", "chk-1");
        citation.put("locator", new LinkedHashMap<>(LOCATOR));
        citation.put("quote", "같은 시간대 예약은 거절한다");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer_status", "ANSWERED");
        result.put("grounded", true);
        result.put("text", "같은 시간대에는 예약할 수 없습니다 [1].");
        result.put("citations", new ArrayList<>(List.of(citation)));
        result.put("sources", new ArrayList<>(List.of(Map.of("document_id", "302", "document_version_id", "312",
                "version_no", 1, "location", "예약 명세 / L3-L4"))));
        result.put("design_refs", new ArrayList<>());
        result.put("task_refs", new ArrayList<>());
        result.put("unresolved", new ArrayList<>());
        result.put("notices", new ArrayList<>());
        result.put("scope", new LinkedHashMap<>(Map.of("mode", "APPROVED", "baseline_id", "401",
                "document_version_ids", new ArrayList<>(List.of("312", "313")))));
        result.put("embedding_profile_id", "profile-1");
        mutate.accept(result);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("origin", "MODEL");
        response.put("result", result);
        return response;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> citation(Map<String, Object> result) {
        return (Map<String, Object>) ((List<?>) result.get("citations")).get(0);
    }

    private static void rejects(Consumer<Map<String, Object>> mutate, String reason) {
        assertThatThrownBy(() -> BeeAnswerValidator.validate(response(mutate), SENT))
                .isInstanceOf(Rejected.class).hasMessage(reason);
    }

    @Test
    void acceptsAnswerThatMatchesWhatWasSent() {
        assertThat(BeeAnswerValidator.validate(response(r -> { }), SENT)).containsEntry("grounded", true);
    }

    @Test
    void rejectsCitationOfEvidenceThatWasNeverSent() {
        rejects(r -> citation(r).put("evidence_id", "ev-999"), "citation_unknown_evidence");
    }

    @Test
    void rejectsCitationThatPointsToAnotherDocumentVersion() {
        rejects(r -> citation(r).put("document_version_id", "999"), "citation_mismatch");
    }

    @Test
    void rejectsCitationWithAnotherChunkKeyOrLines() {
        rejects(r -> citation(r).put("chunk_key", "chk-other"), "citation_mismatch");
        rejects(r -> citation(r).put("locator", Map.of("type", "text_lines", "start_line", 9, "end_line", 9)), "citation_mismatch");
    }

    @Test
    void rejectsSourceOutsideTheAllowedVersions() {
        rejects(r -> r.put("sources", List.of(Map.of("document_id", "9", "document_version_id", "999",
                "version_no", 1, "location", "다른 프로젝트 / L1-L1"))), "source_out_of_scope");
    }

    @Test
    void rejectsDesignAndTaskReferencesThatWereNotSent() {
        rejects(r -> r.put("design_refs", List.of(Map.of("design_item_id", "1004", "design_item_version_id", "9999"))), "design_ref_unknown");
        rejects(r -> r.put("design_refs", List.of(Map.of("design_item_id", "5", "design_item_version_id", "1104"))), "design_ref_unknown");
        rejects(r -> r.put("task_refs", List.of(Map.of("task_id", "777", "key", "TASK-9", "status", "READY"))), "task_ref_unknown");
    }

    @Test
    void acceptsDesignAndTaskReferencesThatWereSent() {
        BeeAnswerValidator.validate(response(r -> {
            r.put("design_refs", List.of(Map.of("design_item_id", "1004", "design_item_version_id", "1104")));
            r.put("task_refs", List.of(Map.of("task_id", "501", "key", "TASK-1", "status", "READY")));
        }), SENT);
    }

    @Test
    void rejectsScopeOrProfileDifferentFromTheRequest() {
        rejects(r -> r.put("scope", Map.of("mode", "APPROVED", "baseline_id", "999", "document_version_ids", List.of("312", "313"))), "scope_mismatch");
        rejects(r -> r.put("scope", Map.of("mode", "APPROVED", "baseline_id", "401", "document_version_ids", List.of("312", "313", "314"))), "scope_mismatch");
        rejects(r -> r.put("scope", Map.of("mode", "DOCUMENT_REVIEW", "baseline_id", "401", "document_version_ids", List.of("312", "313"))), "scope_mismatch");
        rejects(r -> r.put("embedding_profile_id", "other"), "profile_mismatch");
    }

    @Test
    void rejectsBodyMarkerWithoutMatchingCitation() {
        rejects(r -> r.put("text", "예약은 막습니다 [2]."), "text_mark_out_of_range");
        rejects(r -> r.put("text", "예약은 막습니다 [0]."), "text_mark_out_of_range");
    }

    @Test
    void rejectsCitationNumbersThatAreNotOneToK() {
        rejects(r -> citation(r).put("n", 2), "citation_number_gap");
    }

    @Test
    void rejectsGroundedFlagThatDisagreesWithTheEvidence() {
        rejects(r -> r.put("grounded", false), "grounded_mismatch");
        rejects(r -> {
            r.put("citations", new ArrayList<>());
            r.put("text", "근거 없이 답합니다.");
        }, "grounded_mismatch");
    }

    @Test
    void notAnsweredResponseMustCarryNoGround() {
        rejects(r -> r.put("answer_status", "INSUFFICIENT_EVIDENCE"), "grounded_mismatch");
        BeeAnswerValidator.validate(response(r -> {
            r.put("answer_status", "INSUFFICIENT_EVIDENCE");
            r.put("grounded", false);
            r.put("citations", new ArrayList<>());
            r.put("sources", new ArrayList<>());
            r.put("text", "찾지 못했어요.");
        }), SENT);
    }

    @Test
    void rejectsTemplateOriginAndMalformedBodies() {
        Map<String, Object> template = response(r -> { });
        template.put("origin", "TEMPLATE");
        assertThatThrownBy(() -> BeeAnswerValidator.validate(template, SENT)).isInstanceOf(Rejected.class);
        assertThatThrownBy(() -> BeeAnswerValidator.validate(null, SENT)).isInstanceOf(Rejected.class);
        assertThatThrownBy(() -> BeeAnswerValidator.validate(Map.of("origin", "MODEL"), SENT)).isInstanceOf(Rejected.class);
        rejects(r -> r.put("text", " "), "text_length");
        rejects(r -> r.put("answer_status", "MAYBE"), "answer_status_unknown");
    }
}
