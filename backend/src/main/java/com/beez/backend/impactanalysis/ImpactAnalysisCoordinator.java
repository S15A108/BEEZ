package com.beez.backend.impactanalysis;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal, synchronous AI-A03 handoff. No public endpoint, durable result store, or approval is
 * implemented here. Rule candidates are not connected; this never changes task state.
 */
@Service
public class ImpactAnalysisCoordinator {
    public enum Status { ANALYZED, INSUFFICIENT_EVIDENCE, AI_FAILED, STALE }
    public enum UsageStatus { REPORTED, UNKNOWN, NOT_STARTED }

    /**
     * aiEnvelope is retained for diagnostics and may report SUFFICIENT only within the supplied
     * AI input. It is not the final evaluation status and an empty list is not zero impact.
     * #197 must use the top-level status and atomically recheck freshness when persisting;
     * that storage transaction and the shared rule candidate service are not connected here.
     */
    public record Outcome(ImpactContextQuery.Snapshot input, Status status, Map<String, Object> aiEnvelope,
                          List<?> usage, UsageStatus usageStatus, String errorCode) {
        public Outcome {
            usage = List.copyOf(usage);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> AI_CODES = Set.of("model_timeout", "model_unavailable",
            "invalid_ai_input", "invalid_output", "validation_failed", "invalid_internal_token");
    private static final Set<String> STALE_CODES = Set.of("baseline_changed", "baseline_inconsistent",
            "baseline_missing", "proposal_not_analyzable", "analysis_input_missing", "analysis_input_changed");
    private static final int MAX_ERROR_BODY_BYTES = 65536;
    private final ImpactContextQuery query;
    private final AiClient ai;

    public ImpactAnalysisCoordinator(ImpactContextQuery query, AiClient ai) {
        this.query = query;
        this.ai = ai;
    }

    /** requestKey identifies this analysis; the AI server issues a unique key for every LLM call. */
    public Outcome analyze(CallerContext caller, long projectId, long proposalId, String requestKey) {
        ImpactContextQuery.Snapshot snapshot = query.load(caller, projectId, proposalId);
        // V2 does not persist the proposed schema_version required by AI-A03.
        // Do not guess it from the old item or invoke a paid model with an invalid request.
        if (snapshot.changes().stream().anyMatch(change ->
                "CREATE".equals(change.operation()) || "UPDATE".equals(change.operation()))) {
            return new Outcome(snapshot, Status.INSUFFICIENT_EVIDENCE, null, List.of(),
                    UsageStatus.NOT_STARTED, "change_schema_version_unavailable");
        }
        Map<String, Object> request = ImpactRequestAssembler.assemble(snapshot, requestKey);
        Map<String, Object> envelope = null;
        List<?> usage = List.of();
        Status status;
        String errorCode = null;
        try {
            envelope = ai.changeImpact(request);
            usage = extractUsage(envelope);
            if (envelope == null || !(envelope.get("result") instanceof Map<?, ?> result)
                    || !(result.get("candidates") instanceof List<?>)
                    || !(result.get("evidence_status") instanceof String evidence)
                    || !("SUFFICIENT".equals(evidence) || "INSUFFICIENT".equals(evidence))
                    || !candidatesBelongToRequest((List<?>) result.get("candidates"), request)) {
                status = Status.AI_FAILED;
                errorCode = "invalid_output";
                envelope = null;
            } else {
                // AI-only output can remain available for review, but cannot imply that
                // the separately owned rule candidate phase completed.
                status = Status.INSUFFICIENT_EVIDENCE;
                if (snapshot.ruleCandidateStatus() == ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED) {
                    errorCode = "rule_candidates_not_connected";
                }
            }
        } catch (RestClientResponseException ex) {
            Map<String, Object> error = parseError(ex);
            usage = extractUsage(error);
            Object detail = error.get("error");
            Object code = detail instanceof Map<?, ?> map ? map.get("code") : null;
            errorCode = code instanceof String text && AI_CODES.contains(text) ? text : "model_unavailable";
            status = Status.AI_FAILED;
        } catch (RestClientException ex) {
            // A broken connection does not prove whether a provider call started.
            errorCode = "model_unavailable";
            status = Status.AI_FAILED;
        }

        try {
            query.requireUnchanged(caller, snapshot);
        } catch (ApiException e) {
            if (!STALE_CODES.contains(e.getCode())) throw e; // do not disclose data after access loss
            status = Status.STALE;
            errorCode = "analysis_input_changed";
            envelope = null;
        }
        return new Outcome(snapshot, status, envelope, usage,
                usage.isEmpty() ? UsageStatus.UNKNOWN : UsageStatus.REPORTED, errorCode);
    }

    private static List<?> extractUsage(Map<String, Object> data) {
        if (data == null || !(data.get("usage") instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            return List.of();
        }
        return list;
    }

    /** Treat the internal AI response as untrusted and enforce the same request-bound identifiers. */
    private static boolean candidatesBelongToRequest(List<?> candidates, Map<String, Object> request) {
        if (!(request.get("approved_items") instanceof List<?> items)
                || !(request.get("open_tasks") instanceof List<?> tasks)) return false;
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map<?, ?> value)
                    || !(value.get("reason") instanceof String reason) || reason.isBlank()
                    || !(value.get("sources") instanceof List<?> sources)) return false;
            Object taskId = value.get("task_id"), memberId = value.get("member_id");
            Object itemId = value.get("design_item_id"), validationId = value.get("validation_id");
            if (taskId == null && memberId == null && itemId == null && validationId == null) return false;
            if (taskId != null && !(taskId instanceof String)) return false;
            if (memberId != null && !(memberId instanceof String)) return false;
            if (itemId != null && !(itemId instanceof String)) return false;
            if (validationId != null && !(validationId instanceof String)) return false;
            if (itemId != null && items.stream().noneMatch(i -> i instanceof Map<?, ?> item
                    && itemId.equals(item.get("design_item_id")))) return false;
            if (taskId != null || memberId != null || validationId != null) {
                boolean belongs = tasks.stream().anyMatch(t -> {
                    if (!(t instanceof Map<?, ?> task)) return false;
                    if (taskId != null && !taskId.equals(task.get("id"))) return false;
                    if (memberId != null && !memberId.equals(task.get("assignee_member_id"))) return false;
                    if (validationId != null) {
                        Object validation = task.get("validation");
                        if (!(validation instanceof Map<?, ?> v) || !validationId.equals(v.get("id"))) return false;
                    }
                    return true;
                });
                if (!belongs) return false;
            }
            for (Object source : sources) {
                if (!(source instanceof Map<?, ?>) || items.stream().noneMatch(i ->
                        i instanceof Map<?, ?> item && source.equals(item.get("source")))) return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseError(RestClientResponseException ex) {
        byte[] raw = ex.getResponseBodyAsByteArray();
        if (raw.length == 0 || raw.length > MAX_ERROR_BODY_BYTES) return Map.of();
        try {
            Object value = JSON.readValue(raw, Object.class);
            return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }
}
