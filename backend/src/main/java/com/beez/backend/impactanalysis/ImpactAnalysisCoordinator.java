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
 * implemented here. #197 stores rule candidates separately; this never changes task state.
 */
@Service
public class ImpactAnalysisCoordinator {
    public enum Status { ANALYZED, INSUFFICIENT_EVIDENCE, AI_FAILED, STALE }
    public enum UsageStatus { REPORTED, UNKNOWN, NOT_STARTED }

    /**
     * aiEnvelope is retained for diagnostics and may report SUFFICIENT only within the supplied
     * AI input. It is not the final evaluation status and an empty list is not zero impact.
     * #197 must use the top-level status and atomically recheck freshness when persisting;
     * #197 owns that storage transaction and rule candidate calculation.
     */
    public record Outcome(ImpactContextQuery.Snapshot input, Status status, Map<String, Object> aiEnvelope,
                          List<?> usage, UsageStatus usageStatus, String errorCode, String model) {
        public Outcome {
            usage = List.copyOf(usage);
        }

        /** 모델을 부르지 않은 결과 */
        public Outcome(ImpactContextQuery.Snapshot input, Status status, Map<String, Object> aiEnvelope,
                       List<?> usage, UsageStatus usageStatus, String errorCode) {
            this(input, status, aiEnvelope, usage, usageStatus, errorCode, null);
        }
    }

    /**
     * 모델 응답을 받은 뒤 권한 재확인이 결과를 거절했을 때 던진다. 결과·입력은 버리지만 이미 받은 사용량은 호출한 쪽이 기록해야 하므로
     * (비용 집계 MON-A02, -169) 그것만 싣는다. 승인 전 분석·발행 뒤 분석 모두 쓴다. 상태·코드는 원래 오류 그대로.
     */
    static final class AccessRecheckException extends ApiException {
        private final List<?> usage;
        private final String model;

        AccessRecheckException(ApiException cause, List<?> usage, String model) {
            super(cause.getStatus(), cause.getCode(), cause.getMessage());
            this.usage = List.copyOf(usage);
            this.model = model;
        }

        List<?> usage() { return usage; }
        UsageStatus usageStatus() { return usage.isEmpty() ? UsageStatus.UNKNOWN : UsageStatus.REPORTED; }
    }

    /**
     * 모델 응답을 받은 뒤 예상하지 못한 오류(응답 검사 중 형 변환, 재확인 조회의 DB 오류 등)로 분석을 끝내지 못했을 때.
     * 원래 오류를 감싸 그대로 올리고(호출한 쪽의 중단·재시도 처리는 예전과 같음) 받은 사용량만 싣는다 (-169)
     */
    static final class ReceivedUsageException extends RuntimeException {
        private final List<?> usage;
        private final String model;

        ReceivedUsageException(RuntimeException cause, List<?> usage, String model) {
            super(cause.getMessage(), cause);
            this.usage = List.copyOf(usage);
            this.model = model;
        }
    }

    /** 실패에 실려 온, 이미 받은 모델 사용량. 실려 온 것이 없으면 빈 목록 (모델을 부르기 전 실패 등) */
    static List<?> receivedUsage(Throwable failure) {
        if (failure instanceof AccessRecheckException e) return e.usage;
        if (failure instanceof ReceivedUsageException e) return e.usage;
        return List.of();
    }

    /** 실패에 실려 온 응답 최상위 model. 사용량 항목에 model 이 없을 때 공통 사용량 표에 쓴다. 없으면 null */
    static String receivedModel(Throwable failure) {
        if (failure instanceof AccessRecheckException e) return e.model;
        if (failure instanceof ReceivedUsageException e) return e.model;
        return null;
    }

    /** 응답 최상위 model (글자이고 비어 있지 않을 때) */
    private static String modelOf(Map<String, Object> envelope) {
        if (envelope != null && envelope.get("model") instanceof String value && !value.isBlank()) return value;
        return null;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // model_output_truncated: 출력 상한에 닿아 끊김. model_unavailable 로 접으면 장애로 보여 발행 후 분석이 같은 상한으로
    // 재시도만 반복했다. 그대로 남겨 원인을 가리고, 발행 후 분석은 첫 잘림만 한 번 더 시도한다
    // (PublishedAnalysisProcessor.retryableAiFailure, S15P31A108-402)
    private static final Set<String> AI_CODES = Set.of("model_timeout", "model_unavailable", "model_output_truncated",
            "invalid_ai_input", "invalid_output", "validation_failed", "invalid_internal_token");
    private static final Set<String> STALE_CODES = Set.of("baseline_changed", "baseline_inconsistent",
            "baseline_missing", "proposal_not_analyzable", "analysis_input_missing", "analysis_input_changed",
            "analysis_snapshot_invalid");
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
        return analyze(snapshot, requestKey, () -> query.requireUnchanged(caller, snapshot));
    }

    public Outcome analyzePublished(CallerContext caller, long projectId, long proposalId,
                                    long publishedBaseline, String requestKey) {
        ImpactContextQuery.Snapshot snapshot = query.loadPublished(caller, projectId, proposalId, publishedBaseline);
        return analyze(snapshot, requestKey, () -> query.requirePublishedUnchanged(caller, snapshot, publishedBaseline));
    }

    private Outcome analyze(ImpactContextQuery.Snapshot snapshot, String requestKey, Runnable recheck) {
        // Until document diffs have an agreed AI-A03 contract, mixed and document-only
        // proposals must not produce an apparently complete partial analysis.
        if (snapshot.documentChanges()) {
            return new Outcome(snapshot, Status.INSUFFICIENT_EVIDENCE, null, List.of(),
                    UsageStatus.NOT_STARTED, "document_change_unavailable");
        }
        // #195 stores schema_version in the immutable revision. Legacy submissions may not.
        // Never infer a version or call the model with incomplete CREATE/UPDATE content.
        if (snapshot.changes().stream().anyMatch(change ->
                ("CREATE".equals(change.operation()) || "UPDATE".equals(change.operation()))
                        && (change.schemaVersion() == null || change.schemaVersion() < 1))) {
            return new Outcome(snapshot, Status.INSUFFICIENT_EVIDENCE, null, List.of(),
                    UsageStatus.NOT_STARTED, "change_schema_version_unavailable");
        }
        if (snapshot.changes().stream().anyMatch(change ->
                ("CREATE".equals(change.operation()) || "UPDATE".equals(change.operation()))
                        && (change.proposedContent() == null || "{}".equals(change.proposedContent())))) {
            return new Outcome(snapshot, Status.INSUFFICIENT_EVIDENCE, null, List.of(),
                    UsageStatus.NOT_STARTED, "change_content_unavailable");
        }
        // With no approved designs and no related tasks there is no authorized target
        // for the model to recommend. This is missing coverage, not zero impact.
        if (snapshot.approvedItems().isEmpty() && snapshot.taskReferences().isEmpty()) {
            return new Outcome(snapshot, Status.INSUFFICIENT_EVIDENCE, null, List.of(),
                    UsageStatus.NOT_STARTED, "analysis_candidates_unavailable");
        }
        Map<String, Object> request = ImpactRequestAssembler.assemble(snapshot, requestKey);
        Map<String, Object> envelope = null;
        List<?> usage = List.of();
        Status status;
        String errorCode = null;
        boolean responded = false;
        String model = null;
        try {
            envelope = ai.changeImpact(request);
            responded = true;
            model = modelOf(envelope);
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
                // SUFFICIENT only describes the input the model received. If the bounded search dropped
                // designs or tasks, or no search ran at all (no usable terms, input too large), the result cannot be
                // presented as a complete evaluation.
                var coverage = snapshot.searchCoverage();
                boolean searchIncomplete = coverage.termsTruncated() || coverage.designsTruncated()
                        || coverage.tasksTruncated() || coverage.status() != ImpactKeywordSearch.Status.SEARCHED;
                status = snapshot.ruleCandidateStatus() == ImpactContextQuery.RuleCandidateStatus.CONNECTED
                        && "SUFFICIENT".equals(evidence) && !searchIncomplete ? Status.ANALYZED : Status.INSUFFICIENT_EVIDENCE;
                if (searchIncomplete) errorCode = "search_coverage_incomplete";
                else if ("PUBLISHED".equals(snapshot.proposal().status())
                        && snapshot.ruleCandidateStatus() == ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED)
                    errorCode = "publication_relations_unavailable";
            }
        } catch (RestClientResponseException ex) {
            Map<String, Object> error = parseError(ex);
            usage = extractUsage(error);
            model = modelOf(error);
            Object detail = error.get("error");
            Object code = detail instanceof Map<?, ?> map ? map.get("code") : null;
            errorCode = code instanceof String text && AI_CODES.contains(text) ? text : "model_unavailable";
            status = Status.AI_FAILED;
        } catch (RestClientException ex) {
            // A broken connection does not prove whether a provider call started.
            errorCode = "model_unavailable";
            status = Status.AI_FAILED;
        } catch (RuntimeException ex) {
            // 응답을 받기 전의 오류는 원래대로 올린다. 받은 뒤 응답 검사 중 예상하지 못한 오류(형 변환 등)도 예전처럼 올리되
            // (호출한 쪽의 오류 코드·재시도 여부가 바뀌지 않게) 받은 사용량을 함께 싣는다
            if (!responded) throw ex;
            throw new ReceivedUsageException(ex, usage, model);
        }

        try {
            recheck.run();
        } catch (ApiException e) {
            if (!STALE_CODES.contains(e.getCode())) {
                // Never return the input or AI result after access loss. Both callers still need the
                // already-received usage for cost accounting (llm_usage) and their attempt history.
                throw new AccessRecheckException(e, usage, model);
            }
            status = Status.STALE;
            errorCode = "analysis_input_changed";
            envelope = null;
        } catch (RuntimeException e) {
            // 재확인 조회 자체가 실패(DB 등). 원래 오류는 그대로 올리되 받은 사용량을 함께 싣는다
            throw new ReceivedUsageException(e, usage, model);
        }
        return new Outcome(snapshot, status, envelope, usage,
                usage.isEmpty() ? UsageStatus.UNKNOWN : UsageStatus.REPORTED, errorCode, model);
    }

    private static List<?> extractUsage(Map<String, Object> data) {
        if (data == null || !(data.get("usage") instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            return List.of();
        }
        return list;
    }

    /** Treat the internal AI response as untrusted and enforce the same request-bound identifiers. */
    static boolean candidatesBelongToRequest(List<?> candidates, Map<String, Object> request) {
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
