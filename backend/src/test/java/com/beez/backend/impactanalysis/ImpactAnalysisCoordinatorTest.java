package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.ImpactRelationReader.ConfirmedRelation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

class ImpactAnalysisCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ImpactContextQuery.Snapshot snapshot() {
        var proposal = new ImpactContextQuery.Proposal(71, 1, 9, 1, "PROPOSED", "보안", NOW, 2, "sha256:abc");
        var change = new ImpactContextQuery.Change(0, "RETIRE", 10L, 100L, null, null, null, NOW);
        var approved = new ImpactContextQuery.ApprovedItem(10, 100, "API-1", "API_CONTRACT", 1, 1,
                "member", "{\"path\":\"/v1\"}", 7L, 8L, 1,
                "{\"type\":\"text_lines\",\"start_line\":5,\"end_line\":7}");
        var relation = new ConfirmedRelation(9, 20, 200, 10, 100, "DEPENDS_ON", "EXPLICIT", "관련", "4", NOW);
        var direct = ref(10, 100);
        var second = ref(20, 200);
        return new ImpactContextQuery.Snapshot(proposal, List.of(change), List.of(approved), List.of(relation),
                List.of(direct, second), ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
    }

    private ImpactContextQuery.TaskReference ref(long itemId, long versionId) {
        return new ImpactContextQuery.TaskReference(30, "TASK-1", "READY", null, 0, NOW, NOW, 40L, 50, 1,
                "작업", "목표", "[\"회원 조회\"]", "[]", "방법", "[]", "[]", "[]", false,
                null, 4, null, NOW, itemId, versionId, "READS", null, 60L, "PASS");
    }

    @Test
    void mapsApprovedSourceAndCombinesSameTasksReferences() {
        var body = ImpactRequestAssembler.assemble(snapshot(), "request-1");
        assertThat(body.get("reason")).isEqualTo("보안");
        var dependencies = (List<?>) body.get("explicit_dependencies");
        var relation = (Map<?, ?>) dependencies.getFirst();
        assertThat(relation.get("from_design_item_id")).isEqualTo("20");
        assertThat(relation.get("to_design_item_id")).isEqualTo("10");
        assertThat(relation.get("status")).isEqualTo("CONFIRMED");
        assertThat(relation.containsKey("confirmation_event_id")).isFalse();
        var approved = (List<?>) body.get("approved_items");
        var source = (Map<?, ?>) ((Map<?, ?>) approved.getFirst()).get("source");
        assertThat(source.get("location")).isEqualTo("lines 5-7");
        var tasks = (List<?>) body.get("open_tasks");
        assertThat(tasks).hasSize(1);
        var task = (Map<?, ?>) tasks.getFirst();
        var revision = (Map<?, ?>) task.get("current_plan_revision");
        var plan = (Map<?, ?>) revision.get("plan");
        assertThat((List<?>) plan.get("design_refs")).hasSize(2);
        assertThat(((Map<?, ?>) task.get("validation")).get("id")).isEqualTo("60");
        var change = (Map<?, ?>) ((List<?>) body.get("changes")).getFirst();
        assertThat(change.containsKey("schema_version")).isFalse();
    }

    @Test
    void insufficientAiResultRetainsUnconnectedRuleStateAndRechecksSnapshot() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        var usage = List.of(Map.of("request_key", "call-1", "failed", false));
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of("candidates", List.of(),
                "evidence_status", "INSUFFICIENT"), "origin", "MODEL", "usage", usage));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.input().ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
        verify(query).requireUnchanged(caller, snapshot);
    }

    @Test
    void sufficientAiWithNoCandidatesDoesNotBecomeCompletedWhenRulesAreUnconnected() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        var aiResult = Map.of("candidates", List.of(), "evidence_status", "SUFFICIENT");
        when(ai.changeImpact(any())).thenReturn(Map.of("result", aiResult,
                "origin", "MODEL", "usage", List.of(Map.of("request_key", "call-1"))));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("rule_candidates_not_connected");
        assertThat(outcome.aiEnvelope().get("result")).isEqualTo(aiResult);
    }

    @Test
    void modelFailurePreservesUnconnectedRuleStateAndReportedUsage() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        byte[] body = "{\"error\":{\"code\":\"model_timeout\"},\"usage\":[{\"request_key\":\"call-1\",\"failed\":true}]}"
                .getBytes(StandardCharsets.UTF_8);
        when(ai.changeImpact(any())).thenThrow(new RestClientResponseException("failed", 504,
                "Gateway Timeout", null, body, StandardCharsets.UTF_8));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.AI_FAILED);
        assertThat(outcome.errorCode()).isEqualTo("model_timeout");
        assertThat(outcome.input().ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
    }

    @Test
    void inputChangedDuringAiCallDoesNotReturnStaleAiResult() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of("candidates", List.of(),
                "evidence_status", "SUFFICIENT"), "usage", List.of(Map.of("request_key", "call-1"))));
        doThrow(new ApiException(HttpStatus.CONFLICT, "analysis_input_changed", "changed"))
                .when(query).requireUnchanged(caller, snapshot);

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.STALE);
        assertThat(outcome.aiEnvelope()).isNull();
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
    }

    @Test
    void rejectsAiCandidateWithAnUnknownTaskAndKeepsReportedUsage() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of(
                "candidates", List.of(Map.of("task_id", "9999", "reason", "위험", "sources", List.of())),
                "evidence_status", "SUFFICIENT"), "usage", List.of(Map.of("request_key", "call-1"))));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.AI_FAILED);
        assertThat(outcome.errorCode()).isEqualTo("invalid_output");
        assertThat(outcome.aiEnvelope()).isNull();
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
    }

    @Test
    void acceptsOnlyTheValidationAndSourceActuallyProvidedForThatTask() {
        var snapshot = snapshot();
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);
        var source = Map.of("document_id", "7", "document_version_id", "8", "version_no", 1, "location", "lines 5-7");
        var candidate = Map.of("task_id", "30", "member_id", "40", "design_item_id", "10",
                "validation_id", "60", "reason", "변경 검토", "sources", List.of(source));
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of("candidates", List.of(candidate),
                "evidence_status", "SUFFICIENT"), "usage", List.of(Map.of("request_key", "call-1"))));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("rule_candidates_not_connected");
        assertThat(outcome.aiEnvelope()).isNotNull();
    }
    @Test
    void unavailableProposedSchemaVersionRemainsUnassessedAndDoesNotCallAi() {
        var original = snapshot();
        var change = new ImpactContextQuery.Change(0, "UPDATE", 10L, 100L, null, null, "{\"path\":\"/v2\"}", NOW);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), List.of(change), original.approvedItems(),
                original.dependencies(), original.taskReferences(), original.ruleCandidateStatus());
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(input);

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("change_schema_version_unavailable");
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED);
        assertThat(outcome.input().ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "UPDATE"})
    void submittedSchemaVersionIsSentToAi(String operation) {
        var original = snapshot();
        boolean create = "CREATE".equals(operation);
        var change = new ImpactContextQuery.Change(0, operation, create ? null : 10L, create ? null : 100L,
                "API_CONTRACT", 1, "{\"method\":\"POST\",\"path\":\"/login\"}", NOW);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), List.of(change), original.approvedItems(),
                original.dependencies(), original.taskReferences(), original.ruleCandidateStatus());
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(input);
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of("candidates", List.of(),
                "evidence_status", "SUFFICIENT"), "usage", List.of(Map.of("request_key", "call-1"))));

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> request = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(ai).changeImpact(request.capture());
        var body = request.getValue();
        var sent = (Map<?, ?>) ((List<?>) body.get("changes")).getFirst();
        assertThat(sent.get("schema_version")).isEqualTo(1);
        assertThat(sent.get("operation")).isEqualTo(operation);
        assertThat(body.get("proposal_revision")).isEqualTo(2);
        assertThat(body.get("proposal_snapshot_hash")).isEqualTo("sha256:abc");
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
        // The AI response cannot complete the overall analysis while rule candidates are unconnected.
        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void createWithoutAnyApprovedOrTaskCandidatesNeverCallsAiOrClaimsNoImpact() {
        var original = snapshot();
        var change = new ImpactContextQuery.Change(0, "CREATE", null, null, "REQUIREMENT", 1,
                "{\"description\":\"new requirement\"}", NOW);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), List.of(change), List.of(),
                List.of(), List.of(), original.ruleCandidateStatus());
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(input);
        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");
        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("analysis_candidates_unavailable");
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED);
        verifyNoInteractions(ai);
    }

    @Test
    void documentChangeNeverCallsAiWithOnlyPartialDesignChanges() {
        var original = snapshot();
        var input = new ImpactContextQuery.Snapshot(original.proposal(), original.changes(), original.approvedItems(),
                original.dependencies(), original.taskReferences(), original.ruleCandidateStatus(),
                original.searchCoverage(), original.relationSetRevision(), true);
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(input);
        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");
        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("document_change_unavailable");
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED);
        verifyNoInteractions(ai);
    }

    @Test
    void unlinkedSearchTaskIsPassedWithoutInventingDesignReference() {
        var original = snapshot();
        var unlinked = new ImpactContextQuery.TaskReference(31, "TASK-2", "READY", null, 0, NOW, NOW,
                40L, 51, 1, "memberId screen", "show memberId", "[]", "[]", "method", "[]", "[]", "[]",
                false, null, 4, null, NOW, null, null, null, null, null, null);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), original.changes(), original.approvedItems(),
                original.dependencies(), List.of(unlinked), original.ruleCandidateStatus(),
                new ImpactKeywordSearch.Coverage(ImpactKeywordSearch.Status.SEARCHED, 2, false, false, true, List.of(31L)));

        var body = ImpactRequestAssembler.assemble(input, "req");
        var task = (Map<?, ?>) ((List<?>) body.get("open_tasks")).getFirst();
        var revision = (Map<?, ?>) task.get("current_plan_revision");
        var plan = (Map<?, ?>) revision.get("plan");
        assertThat((List<?>) plan.get("design_refs")).isEmpty();
        var search = (Map<?, ?>) body.get("search_context");
        assertThat(search.get("tasks_truncated")).isEqualTo(true);
        assertThat(search.get("candidate_task_ids")).isEqualTo(List.of("31"));
        assertThat(search.get("graph_coverage")).isEqualTo("UNKNOWN");
    }

}
