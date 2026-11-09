package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static com.beez.backend.document.support.DocumentReviewFixtures.result;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.validation.DocumentReviewReferenceHasher;
import com.beez.backend.document.validation.DocumentReviewResultValidator;
import com.beez.backend.designitem.support.DesignJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentReviewResultValidatorTest {
    Map<String, Object> input() {
        var refs = DocumentReviewReferenceHasher.snapshots(List.of(Map.of(
                "document_id", 2, "document_version_id", 3, "type", "ERD", "version_no", 1,
                "content_text", "사용자는 인증해야 한다.")));
        return new HashMap<>(Map.of("document_id", "1", "revision", 1, "content", "로그인한다.\n",
                "kind", "PLAN", "content_hash", DocumentReviewReferenceHasher.textHash("로그인한다.\n"),
                "reference_hash", DocumentReviewReferenceHasher.referenceHash(refs), "request_key", "test", "references", refs));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> output(Map<String, Object> envelope) { return (Map<String, Object>) envelope.get("result"); }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps(Map<String, Object> envelope) { return (List<Map<String, Object>>) output(envelope).get("steps"); }

    Map<String, Object> patch(String original, String replacement) {
        return Map.of("explanation", "권한을 명시하세요.", "original_text", original, "replacement_text", replacement);
    }

    @Test void classificationsAreStoredAsAnEnumPairWhileLegacyResponsesRemainValid() {
        var input = input();
        var classified = new HashMap<String, Object>(patch("로그인한다.", "인증한다."));
        classified.put("category", "REJECTION_FIX"); classified.put("severity", "MAJOR");
        var saved = DocumentReviewResultValidator.validate(result(input, List.of(classified)), input);
        assertThat(saved.get("suggestions").toString()).contains("category=REJECTION_FIX", "severity=MAJOR");
        for (var invalid : List.of(Map.of("category", "OMISSION"), Map.of("severity", "MINOR"),
                Map.of("category", "UNKNOWN", "severity", "MAJOR"), Map.of("category", "OMISSION", "severity", "BLOCKING"))) {
            var row = new HashMap<String, Object>(patch("로그인한다.", "인증한다.")); row.putAll(invalid);
            assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, List.of(row)), input))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("invalid_ai_output"));
        }
        var legacy = DocumentReviewResultValidator.validate(result(input, List.of(patch("로그인한다.", "인증한다."))), input);
        assertThat(legacy.get("suggestions").toString()).doesNotContain("category", "severity");
    }

    @Test void completeAndMissingReferencePartialAreRecomputedAndCommentHasNoReplacement() {
        var input = input();
        var envelope = result(input, List.of(Map.of("type", "COMMENT", "explanation", "본문 설명입니다.")));
        var saved = DocumentReviewResultValidator.validate(envelope, input);
        assertThat(saved.get("review_status")).isEqualTo("PARTIAL");
        assertThat(saved.get("unavailable_references")).isEqualTo(List.of("API_SPEC", "REQ_SPEC"));
        assertThat(saved.get("references_used")).isEqualTo(List.of("document-version-3"));
        assertThat(saved.get("suggestions").toString()).doesNotContain("replacement_text", "decision");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "unknown", "body-partial", "body-same-kind", "fake-complete",
            "foreign-id", "duplicate-id", "wrong-kind", "used-missing", "used-foreign", "hash", "revision", "failure-reason"})
    void rejectsMalformedOrOverstatedReviewScope(String attack) {
        var input = input();
        var envelope = result(input, List.of());
        var output = output(envelope);
        switch (attack) {
            case "missing" -> steps(envelope).removeFirst();
            case "duplicate" -> steps(envelope).set(3, steps(envelope).getFirst());
            case "unknown" -> steps(envelope).getFirst().put("step", "UNKNOWN");
            case "body-partial" -> steps(envelope).getFirst().put("status", "PARTIAL");
            case "body-same-kind" -> steps(envelope).getFirst().put("reason", "SAME_KIND");
            case "fake-complete" -> output.put("review_status", "COMPLETE");
            case "foreign-id" -> steps(envelope).get(2).put("reference_ids", List.of("other-project"));
            case "duplicate-id" -> steps(envelope).get(2).put("reference_ids", List.of("document-version-3", "document-version-3"));
            case "wrong-kind" -> steps(envelope).get(1).putAll(Map.of("status", "COMPLETE", "reason", "REVIEWED", "reference_ids", List.of("document-version-3")));
            case "used-missing" -> output.put("references_used", List.of());
            case "used-foreign" -> output.put("references_used", List.of("other-project"));
            case "hash" -> output.put("content_hash", "wrong");
            case "revision" -> output.put("revision", 1.5);
            case "failure-reason" -> steps(envelope).get(1).put("reason", "MODEL_TIMEOUT");
        }
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(envelope, input))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("invalid_ai_output"));
    }

    @Test void sameKindIsExcludedEvenWhenAnApprovedVersionOfThatKindExists() {
        var input = input(); input.put("kind", "ERD");
        var saved = DocumentReviewResultValidator.validate(result(input, List.of()), input);
        assertThat(saved.get("references_used")).isEqualTo(List.of());
        assertThat(saved.get("steps").toString()).contains("SAME_KIND");
    }

    @Test void sourceMetadataComesFromAuthorizedInputAndQuotesMustExist() {
        var input = input();
        var suggestion = new HashMap<String, Object>(Map.of("type", "COMMENT", "step", "ERD", "explanation", "승인본 근거입니다.",
                "sources", List.of(Map.of("reference_id", "document-version-3", "quote", "인증해야", "document_id", "foreign"))));
        var saved = DocumentReviewResultValidator.validate(result(input, List.of(suggestion)), input);
        assertThat(saved.get("suggestions").toString()).contains("document_id=2").doesNotContain("foreign");
        suggestion.put("sources", List.of(Map.of("reference_id", "document-version-3", "quote", "존재하지 않는 인용")));
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, List.of(suggestion)), input)).isInstanceOf(ApiException.class);
        suggestion.put("sources", List.of(Map.of("reference_id", "other-project")));
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, List.of(suggestion)), input)).isInstanceOf(ApiException.class);
    }

    @Test void rejectsPatchOverlapAmbiguousOrMissingOriginalAndCommentReplacement() {
        var input = input();
        for (List<?> invalid : List.of(
                List.of(patch("로그인한다.", "A"), patch("로그인", "B")),
                List.of(patch("없는 원문", "B")),
                List.of(Map.of("type", "COMMENT", "explanation", "의견", "replacement_text", "수정")))) {
            assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, invalid), input)).isInstanceOf(ApiException.class);
        }
        input.put("content", "로그인한다. 로그인한다.");
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, List.of(patch("로그인한다.", ""))), input))
                .isInstanceOf(ApiException.class);
    }

    @Test void acceptsDeletionButRejectsOpinionOnSkippedStepAndUnapprovedInput() {
        var input = input();
        assertThat(DocumentReviewResultValidator.validate(result(input, List.of(patch("로그인한다.", ""))), input).get("suggestions").toString())
                .contains("replacement_text=");
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input, List.of(
                Map.of("type", "COMMENT", "step", "API_SPEC", "explanation", "비교하지 않은 자료"))), input)).isInstanceOf(ApiException.class);
        input.put("references", List.of(Map.of("reference_id", "draft", "status", "DRAFT")));
        assertThatThrownBy(() -> DocumentReviewResultValidator.validate(result(input(), List.of()), input)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "complete"})
    void fixedFixtureIsAcceptedWithoutSynthesizingModelWork(String status) throws Exception {
        try (var stream = getClass().getResourceAsStream("/document/review-contract-" + status + ".json")) {
            var fixture = DesignJson.JSON.readValue(stream, Map.class);
            var saved = DocumentReviewResultValidator.validate((Map<String, Object>) fixture.get("response"),
                    (Map<String, Object>) fixture.get("input"));
            assertThat(saved.get("review_status")).isEqualTo(status.toUpperCase(Locale.ROOT));
        }
    }
}
