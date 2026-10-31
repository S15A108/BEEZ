package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Business decisions with fake JDBC; does not test PostgreSQL locking or transaction rollback. */
class ValidationOutcomeWriterFlowTest {
    private record Write(String sql, Map<String, Object> params) {}
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ValidationRuleChecker rules = mock(ValidationRuleChecker.class);
    private final ValidationInputAssembler assembler = mock(ValidationInputAssembler.class);
    private final RelatedTaskCandidateFinder related = mock(RelatedTaskCandidateFinder.class);
    private final List<Write> writes = new ArrayList<>();
    private final ValidationOutcomeWriter.Execution execution = new ValidationOutcomeWriter.Execution(
            1, 2, 3, 4, 1, UUID.randomUUID(), 5, 6, Map.of(), "gms", CallerContext.human(1L));
    private final ValidationOutcomeWriter writer = new ValidationOutcomeWriter(jdbc, JsonMapper.builder().build(), rules, assembler, related);

    @BeforeEach
    void setup() {
        when(related.find(any(), eq(1L), eq(2L), eq(6L), anyList(), eq(20)))
                .thenReturn(new RelatedTaskCandidateFinder.Result(5, 6, List.of(), false,
                        RelatedTaskCandidateFinder.Coverage.RECORDED_RELATIONSHIPS_ONLY));
        when(rules.check(1, 2, 6, 5)).thenReturn(ruleResult(List.of(), List.of()));
        when(assembler.loadUnresolved(eq(1L), eq(2L), eq(5L), anySet(), nullable(String.class))).thenReturn(List.of());
        when(assembler.loadEvidence(eq(1L), eq(5L), anyList()))
                .thenReturn(new ValidationEvidenceBuilder.Result(List.of(), List.of()));
        when(jdbc.sql(anyString())).thenAnswer(invocation -> statement(invocation.getArgument(0)));
    }

    @SuppressWarnings("unchecked")
    private JdbcClient.StatementSpec statement(String sql) {
        Map<String, Object> params = new HashMap<>();
        JdbcClient.ResultQuerySpec rowQuery = mock(JdbcClient.ResultQuerySpec.class);
        JdbcClient.MappedQuerySpec<Long> countQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(countQuery.single()).thenReturn(1L);
        if (sql.startsWith("select kind,severity from validation_finding")) {
            when(rowQuery.listOfRows()).thenReturn(List.of());
        } else if (sql.startsWith("select id,source_document_version_id from design_item_version")) {
            when(rowQuery.listOfRows()).thenReturn(List.of(Map.of("id", 11L, "source_document_version_id", 20L)));
        } else {
            when(rowQuery.listOfRows()).thenReturn(List.of(Map.of("id", 1L)));
        }
        return mock(JdbcClient.StatementSpec.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "param" -> {
                    params.put(invocation.getArgument(0).toString(), invocation.getArgument(1));
                    return invocation.getMock();
                }
                case "query" -> { return invocation.getArguments().length == 0 ? rowQuery : countQuery; }
                case "update" -> {
                    writes.add(new Write(sql, new HashMap<>(params)));
                    return 1;
                }
                default -> { return RETURNS_DEFAULTS.answer(invocation); }
            }
        });
    }

    private static ValidationRuleChecker.Result ruleResult(List<ValidationRuleChecker.Finding> findings,
                                                            List<String> unsupported) {
        return new ValidationRuleChecker.Result(findings, List.of("REQUIRED_FIELDS"), unsupported);
    }

    private Map<String, Object> request() {
        var request = new LinkedHashMap<String, Object>();
        request.put("project_id", "1"); request.put("validation_id", "3"); request.put("attempt", 1);
        request.put("input_snapshot_hash", "sha256:test");
        request.put("plan_revision", Map.of("id", "6", "task_id", "2", "plan", Map.of("design_refs", List.of(
                Map.of("design_item_id", "10", "design_item_version_id", "11", "relation", "READS")))));
        request.put("unresolved", List.of());
        request.put("approved_items", List.of(Map.of("id", "11", "design_item_id", "10", "kind", "API_CONTRACT",
                "content", Map.of("method", "GET", "path", "/members/{memberId}", "description", "회원 조회",
                        "request_fields", List.of(Map.of("name", "memberId", "location", "path", "type", "integer", "required", true)),
                        "responses", List.of(Map.of("status_code", "200", "fields", List.of(
                                Map.of("name", "memberId", "type", "integer", "required", true))))))));
        request.put("related_plans", List.of());
        request.put("checks", List.of(
                Map.of("key", "plan", "design_item_version_ids", List.of(), "related_plan_revision_ids", List.of()),
                Map.of("key", "referenced-design-11", "design_item_version_ids", List.of("11"), "related_plan_revision_ids", List.of())));
        request.put("evidence", List.of());
        return request;
    }

    private static Map<String, Object> response(Map<String, Object> result) {
        return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                "request_key", "llm-validate-3-attempt-1-call-1", "feature", "TASK_VALIDATION",
                "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 12)), "result", result);
    }

    private static Map<String, Object> complete(List<Map<String, Object>> findings) {
        return Map.of("analysis_status", "COMPLETE", "checked_keys", List.of("plan", "referenced-design-11"),
                "unassessed", List.of(), "findings", findings);
    }

    private Write validationWrite() {
        return writes.stream().filter(w -> w.sql().startsWith("update validation set status"))
                .findFirst().orElseThrow();
    }

    private boolean wrote(String fragment) { return writes.stream().anyMatch(w -> w.sql().contains(fragment)); }

    @Test void completeNormalPlanSavesPassUsageAndReference() {
        assertThat(writer.complete(execution, request(), response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.APPLIED);
        assertThat(validationWrite().params()).containsEntry("status", "PASS");
        assertThat(wrote("insert into llm_usage")).isTrue();
        assertThat(wrote("insert into validation_design_ref")).isTrue();
        assertThat(wrote("insert into validation_finding")).isFalse();
    }

    @Test void newlyRelatedTaskDiscardsPreviouslyAnalyzedResult() {
        var newlyRelated = new RelatedTaskCandidateFinder.Candidate(99, 100, 1L, List.of());
        when(related.find(any(), eq(1L), eq(2L), eq(6L), eq(List.of(10L)), eq(20)))
                .thenReturn(new RelatedTaskCandidateFinder.Result(5, 6, List.of(newlyRelated), false,
                        RelatedTaskCandidateFinder.Coverage.RECORDED_RELATIONSHIPS_ONLY));
        assertThat(writer.complete(execution, request(), response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(validationWrite().sql()).contains("status='STALE'");
        assertThat(wrote("insert into validation_finding")).isFalse();
    }

    @Test void truncatedRelatedTaskSearchCannotApplyOldResult() {
        when(related.find(any(), eq(1L), eq(2L), eq(6L), anyList(), eq(20)))
                .thenReturn(new RelatedTaskCandidateFinder.Result(5, 6, List.of(), true,
                        RelatedTaskCandidateFinder.Coverage.RECORDED_RELATIONSHIPS_ONLY));
        assertThat(writer.complete(execution, request(), response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(validationWrite().sql()).contains("status='STALE'");
    }

    @Test void extraSearchCandidatePreventsAutomaticPass() {
        var request = request();
        @SuppressWarnings("unchecked")
        var items = new ArrayList<>((List<Map<String, Object>>) request.get("approved_items"));
        items.add(Map.of("id", "21", "design_item_id", "20", "kind", "POLICY",
                "content", Map.of("description", "회원 조회 권한 정책")));
        request.put("approved_items", items);
        assertThat(writer.complete(execution, request, response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.APPLIED);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> {
            assertThat(w.sql()).contains("insert into validation_finding");
            assertThat(w.params()).containsEntry("message", "rules_unverified").containsEntry("resolution", "HUMAN_REVIEW");
        });
        assertThat(wrote("insert into validation_design_ref")).isFalse();
    }

    @Test void newBlockingRuleAfterAiDiscardsResultAsStale() {
        var conflict = new ValidationRuleChecker.Finding("CONFLICT", "BLOCKING", "다른 작업 충돌",
                10L, 11L, 8L, "HUMAN_REVIEW", "확인");
        when(rules.check(1, 2, 6, 5)).thenReturn(ruleResult(List.of(conflict), List.of()));
        assertThat(writer.complete(execution, request(), response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(validationWrite().sql()).contains("status='STALE'");
        assertThat(wrote("insert into llm_usage")).isTrue();
        assertThat(wrote("insert into validation_finding")).isFalse();
    }

    @Test void unsupportedRulesDoNotAllowPass() {
        when(rules.check(1, 2, 6, 5)).thenReturn(ruleResult(List.of(), List.of("STRUCTURED_CONTRACT_MISMATCH")));
        writer.complete(execution, request(), response(complete(List.of())), true);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> assertThat(w.params()).containsEntry("resolution", "HUMAN_REVIEW"));
    }

    @Test void apiFieldsAbsentFromApprovedContentCannotProducePass() {
        var request = request();
        request.put("approved_items", List.of(Map.of("id", "11", "design_item_id", "10", "kind", "API_CONTRACT",
                "content", Map.of("method", "GET", "path", "/members/{memberId}", "description", "회원 조회"))));
        writer.complete(execution, request, response(complete(List.of())), true);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> assertThat(w.params()).containsEntry("resolution", "HUMAN_REVIEW"));
    }

    @Test void partialAnalysisAndTruncatedSearchCannotPass() {
        var partial = Map.<String, Object>of("analysis_status", "PARTIAL", "checked_keys", List.of("plan"),
                "unassessed", List.of(Map.of("check_key", "referenced-design-11", "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 없음")),
                "findings", List.of());
        writer.complete(execution, request(), response(partial), true);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> assertThat(w.params()).containsEntry("resolution", "HUMAN_REVIEW"));
        writes.clear();
        writer.complete(execution, request(), response(complete(List.of())), false);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        verify(rules, never()).check(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void confirmedMissingInputCanReturnNeedsInput() {
        var missing = Map.<String, Object>of("check_key", "plan", "kind", "MISSING_INPUT", "severity", "BLOCKING",
                "message", "완료 기준 누락", "sources", List.of(), "evidence_ids", List.of(), "suggested_action", "기준을 작성하세요");
        writer.complete(execution, request(), response(complete(List.of(missing))), true);
        assertThat(validationWrite().params()).containsEntry("status", "NEEDS_INPUT");
        assertThat(writes).anySatisfy(w -> {
            assertThat(w.sql()).contains("insert into validation_finding");
            assertThat(w.params()).containsEntry("kind", "MISSING_INPUT").containsEntry("resolution", "FIX_PLAN");
        });
    }

    @Test void newlyUnresolvedQuestionAfterAiDiscardsTheStaleResult() {
        when(assembler.loadUnresolved(eq(1L), eq(2L), eq(5L), anySet(), nullable(String.class)))
                .thenReturn(List.of("planning_question:7 회원 조회 권한이 미정입니다"));
        assertThat(writer.complete(execution, request(), response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(validationWrite().sql()).contains("status='STALE'");
        assertThat(wrote("insert into llm_usage")).isTrue();
        assertThat(wrote("insert into validation_finding")).isFalse();
        var order = inOrder(jdbc, assembler);
        order.verify(jdbc).sql("lock table planning_question in share mode");
        order.verify(assembler).loadUnresolved(eq(1L), eq(2L), eq(5L), eq(java.util.Set.of(10L)), isNull());
    }

    @Test void unverifiedContextPacketCannotProducePass() {
        var request = request();
        request.put("plan_revision", Map.of("id", "6", "task_id", "2", "plan", Map.of(
                "context_packet_id", "80", "design_refs", List.of(
                    Map.of("design_item_id", "10", "design_item_version_id", "11", "relation", "READS")))));
        writer.complete(execution, request, response(complete(List.of())), true);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> assertThat(w.params()).containsEntry("resolution", "HUMAN_REVIEW"));
    }

    @Test void changedOriginalEvidenceDiscardsAnalysisAndPreservesUsage() {
        var request = request();
        var original = Map.<String, Object>of("evidence_id", "original-11", "design_item_version_id", "11",
                "source", Map.of("document_id", "19", "document_version_id", "20", "version_no", 1, "location", "API"),
                "text", "회원 조회 v1");
        var changed = new HashMap<>(original);
        changed.put("text", "회원 조회 v2");
        request.put("evidence", List.of(original));
        when(assembler.loadEvidence(eq(1L), eq(5L), anyList()))
                .thenReturn(new ValidationEvidenceBuilder.Result(List.of(changed), List.of()));
        assertThat(writer.complete(execution, request, response(complete(List.of())), true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(validationWrite().sql()).contains("status='STALE'");
        assertThat(wrote("insert into llm_usage")).isTrue();
        assertThat(wrote("insert into validation_finding")).isFalse();
        verify(rules, never()).check(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void unavailableSourceCannotBecomePassEvenWhenTheModelSaysComplete() {
        when(assembler.loadEvidence(eq(1L), eq(5L), anyList()))
                .thenReturn(new ValidationEvidenceBuilder.Result(List.of(), List.of("11")));
        writer.complete(execution, request(), response(complete(List.of())), true);
        assertThat(validationWrite().sql()).contains("status='ERROR'");
        assertThat(writes).anySatisfy(w -> assertThat(w.params())
                .containsEntry("message", "evidence_insufficient").containsEntry("resolution", "HUMAN_REVIEW"));
        verify(rules, never()).check(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void sourceRowsAreLockedBeforeEvidenceIsRechecked() {
        var request = request();
        writer.complete(execution, request, response(complete(List.of())), true);
        var order = inOrder(jdbc, assembler);
        order.verify(jdbc).sql("select id,source_document_version_id from design_item_version where id in (:ids) order by id for share");
        order.verify(jdbc).sql("select document_version_id from document_processing where document_version_id in (:ids) order by document_version_id for share");
        order.verify(jdbc).sql("select id from document_version where id in (:ids) order by id for share");
        order.verify(assembler).loadEvidence(eq(1L), eq(5L), argThat(items -> items.equals(request.get("approved_items"))));
        assertThat(validationWrite().params()).containsEntry("status", "PASS");
    }
}
