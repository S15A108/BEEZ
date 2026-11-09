package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BeeAnswerValidatorTest {
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    private record Fixture(Map<String, Object> input, Map<String, Object> envelope,
                           Map<String, Object> result, Map<String, Object> citation,
                           Map<String, Object> source, Map<String, Object> usage) { }

    private Fixture fixture() {
        Map<String, Object> locator = row("type", "text_lines", "start_line", 2, "end_line", 4);
        Map<String, Object> scope = row("mode", "APPROVED", "baseline_id", "10", "document_version_ids", List.of("21"));
        Map<String, Object> evidence = row("evidence_id", "e1", "document_id", "20", "document_version_id", "21",
                "version_no", 2, "chunk_key", "chunk", "text", "첫 줄\n정확한 근거 문장\n끝", "locator", locator,
                "embedding_profile_id", "p1", "title", "문서", "section_title", "2. 정책");
        Map<String, Object> input = row("scope", scope, "embedding_profile_id", "p1", "retrieved_evidence", List.of(evidence),
                "approved_items", List.of(), "related_tasks", List.of());
        Map<String, Object> citation = row("n", 1, "evidence_id", "e1", "document_id", "20", "document_version_id", "21",
                "version_no", 2, "chunk_key", "chunk", "locator", locator, "quote", "정확한 근거 문장",
                "title", "문서", "section_title", "2. 정책", "claim", "근거 설명");
        Map<String, Object> source = row("document_id", "20", "document_version_id", "21", "version_no", 2,
                "location", "2. 정책 / L2-L4");
        Map<String, Object> result = row("text", "답변입니다. [1]", "answer_status", "ANSWERED", "grounded", true,
                "scope", scope, "embedding_profile_id", "p1", "citations", new ArrayList<>(List.of(citation)),
                "sources", new ArrayList<>(List.of(source)), "design_refs", List.of(), "task_refs", List.of(),
                "unresolved", List.of(), "verification", row("level", "TOKENS", "claims", 1));
        Map<String, Object> usage = row("request_key", "llm-1", "feature", "BEE_CHAT", "model", "model-a",
                "latency_ms", 20, "failed", false, "tokens_known", false);
        Map<String, Object> envelope = row("origin", "MODEL", "model", "model-a", "usage", List.of(usage), "result", result);
        return new Fixture(input, envelope, result, citation, source, usage);
    }

    private static void rejects(Fixture f) {
        assertThatThrownBy(() -> BeeAnswerValidator.validate(f.envelope(), f.input()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getCode()).isEqualTo("ai_unavailable");
                });
    }

    @Test void validAnswerReturnsCanonicalServerEvidenceAndUsage() {
        Fixture f = fixture();
        Map<String, Object> result = BeeAnswerValidator.validate(f.envelope(), f.input());
        assertThat(result).containsEntry("grounded", true).containsEntry("text", "답변입니다. [1]");
        assertThat((List<?>) result.get("citations")).hasSize(1);
        assertThat(result.get("sources")).isEqualTo(List.of(f.source()));
        assertThat(result.get("usage")).isEqualTo(List.of(f.usage()));
    }

    @Test void rejectsUnknownEvidenceAndForgedDocumentOrQuote() {
        Fixture f = fixture(); f.citation().put("evidence_id", "other"); rejects(f);
        f = fixture(); f.citation().put("document_version_id", "22"); rejects(f);
        f = fixture(); f.citation().put("quote", "문서에 없는 말"); rejects(f);
        f = fixture(); f.citation().put("locator", row("type", "text_lines", "start_line", 3, "end_line", 4)); rejects(f);
    }

    @Test void rejectsUnrelatedSourceProfileScopeAndUnusedCitation() {
        Fixture f = fixture(); f.source().put("location", "허위 위치"); rejects(f);
        f = fixture(); f.result().put("embedding_profile_id", "wrong"); rejects(f);
        f = fixture(); f.result().put("scope", row("mode", "APPROVED", "baseline_id", "11", "document_version_ids", List.of("21"))); rejects(f);
        f = fixture(); f.result().put("text", "인용하지 않은 답변입니다."); rejects(f);
    }

    @Test void rejectsUnverifiedOrUngroundedAnswerAndInvalidUsage() {
        Fixture f = fixture(); f.result().put("verification", row("level", "NONE", "claims", 0)); rejects(f);
        f = fixture(); f.result().put("grounded", false); rejects(f);
        f = fixture(); f.envelope().put("usage", List.of()); rejects(f);
        f = fixture(); f.usage().put("failed", true); rejects(f);
        f = fixture(); f.usage().put("latency_ms", -1); rejects(f);
        f = fixture(); f.usage().put("tokens_known", true); rejects(f);
        f = fixture(); f.usage().put("estimated_cost_amount", "0.01"); rejects(f);
        f = fixture(); f.envelope().put("origin", "TEMPLATE"); rejects(f);
    }

    @Test void ungroundedFixedAnswerIsAllowedButMadeUpTextIsNot() {
        Fixture f = fixture();
        f.result().put("answer_status", "INSUFFICIENT_EVIDENCE"); f.result().put("grounded", false);
        f.result().put("text", "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.");
        f.result().put("citations", List.of()); f.result().put("sources", List.of());
        f.result().put("verification", row("level", "NONE", "claims", 0));
        assertThat(BeeAnswerValidator.validate(f.envelope(), f.input())).containsEntry("grounded", false);
        f.result().put("text", "사실일지도 모릅니다."); rejects(f);
    }

    @Test void returnsOnlyAuthorizedItemAndTaskRefsAndHidesOutOfScopeItemSource() {
        Fixture f = fixture();
        Map<String, Object> oldSource = row("document_id", "20", "document_version_id", "19", "version_no", 1, "location", "옛 버전");
        Map<String, Object> item = row("id", "31", "design_item_id", "30", "key", "API-01", "kind", "API_CONTRACT",
                "version_no", 1, "source", oldSource);
        Map<String, Object> itemRef = row("design_item_version_id", "31", "design_item_id", "30", "key", "API-01",
                "kind", "API_CONTRACT", "version_no", 1, "source", oldSource);
        Map<String, Object> task = row("id", "50", "key", "TASK-01", "status", "READY");
        f.input().put("approved_items", List.of(item)); f.input().put("related_tasks", List.of(task));
        f.result().put("design_refs", List.of(itemRef));
        f.result().put("task_refs", List.of(row("task_id", "50", "key", "TASK-01", "status", "READY")));
        f.result().put("citations", List.of()); f.result().put("sources", List.of()); f.result().put("text", "설계와 작업을 확인하세요.");
        Map<String, Object> result = BeeAnswerValidator.validate(f.envelope(), f.input());
        assertThat((List<?>) result.get("design_refs")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) result.get("design_refs")).getFirst()).containsKey("source")).isFalse();
        assertThat((List<?>) result.get("task_refs")).hasSize(1);
        f.result().put("task_refs", List.of(row("task_id", "999", "key", "TASK-01", "status", "READY")));
        rejects(f);
    }
}
