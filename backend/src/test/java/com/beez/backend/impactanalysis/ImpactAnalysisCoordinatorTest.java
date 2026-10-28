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
import com.beez.backend.ontology.ConfirmedDependency;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

class ImpactAnalysisCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ImpactContextQuery.Snapshot snapshot() {
        var proposal = new ImpactContextQuery.Proposal(71, 1, 9, 1, "PROPOSED", "보안", NOW);
        var change = new ImpactContextQuery.Change(11, "RETIRE", 10L, 100L, null, null, NOW);
        var approved = new ImpactContextQuery.ApprovedItem(10, 100, "API-1", "API_CONTRACT", 1, 1,
                "member", "{\"path\":\"/v1\"}", 7L, 8L, 1,
                "{\"type\":\"text_lines\",\"start_line\":5,\"end_line\":7}");
        var relation = new ConfirmedDependency(77, 9, 20, 200, 10, 100, 4, NOW, "관련");
        var direct = ref(10, 100);
        var second = ref(20, 200);
        var paths = ImpactCandidateFinder.find(List.of(new ImpactCandidateFinder.Change(11, 10L, 100L, "UPDATE")),
                List.of(new ImpactCandidateFinder.Dependency(20, 200, 10, 100, 77)),
                List.of(new ImpactCandidateFinder.PlanReference(30, 40L, 50, 10, 100, "READS")));
        return new ImpactContextQuery.Snapshot(proposal, List.of(change), List.of(approved), List.of(relation),
                List.of(direct, second), paths);
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
    void successReturnsAiOutputAlongsideRulePathsAndRechecksSnapshot() {
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
        assertThat(outcome.input().candidates().paths()).hasSize(1);
        assertThat(outcome.input().candidates().indirectCoverage()).isEqualTo(ImpactCandidateFinder.Coverage.UNKNOWN);
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
        verify(query).requireUnchanged(caller, snapshot);
    }

    @Test
    void modelFailurePreservesRulePathsAndReportedUsage() {
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
        assertThat(outcome.input().candidates().paths()).hasSize(1);
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

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.ANALYZED);
        assertThat(outcome.aiEnvelope()).isNotNull();
    }
    @Test
    void unavailableProposedSchemaVersionKeepsRulesAndDoesNotCallAi() {
        var original = snapshot();
        var change = new ImpactContextQuery.Change(11, "UPDATE", 10L, 100L, null, "{\"path\":\"/v2\"}", NOW);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), List.of(change), original.approvedItems(),
                original.dependencies(), original.taskReferences(), original.candidates());
        var query = mock(ImpactContextQuery.class);
        var ai = mock(AiClient.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(input);

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "req");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("change_schema_version_unavailable");
        assertThat(outcome.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED);
        assertThat(outcome.input().candidates().paths()).hasSize(1);
        verifyNoInteractions(ai);
    }

    @Test
    void unlinkedSearchTaskIsPassedWithoutInventingDesignReference() {
        var original = snapshot();
        var unlinked = new ImpactContextQuery.TaskReference(31, "TASK-2", "READY", null, 0, NOW, NOW,
                40L, 51, 1, "memberId screen", "show memberId", "[]", "[]", "method", "[]", "[]", "[]",
                false, null, 4, null, NOW, null, null, null, null, null, null);
        var input = new ImpactContextQuery.Snapshot(original.proposal(), original.changes(), original.approvedItems(),
                original.dependencies(), List.of(unlinked), original.candidates(),
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
