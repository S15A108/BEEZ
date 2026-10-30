package com.beez.backend.validation.service;

import com.beez.backend.validation.repository.FindingRow;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커가 받은 AI 응답(또는 실패)을 한 트랜잭션으로 반영한다 (공통 "검증 실행 오류 규약").
 * <ul>
 *   <li>먼저 검증을 잠그고 아직 RUNNING·같은 시도·같은 리스인지 본다. 아니면 늦은 결과: 실행이 아직 RUNNING 이면 DISCARDED 로
 *       닫고 검증 상태는 바꾸지 않는다</li>
 *   <li>응답 검사(검사 키 분할·ID·근거)를 통과한 COMPLETE 만 후보를 저장하고 6절 판정표로 PASS·NEEDS_INPUT·NEEDS_REVIEW</li>
 *   <li>PARTIAL·계약 위반·호출 실패는 실행 종결 + ERROR 발견 항목(MODEL·ERROR·BLOCKING) + 검증 ERROR + 작업 FAILED 를 함께</li>
 * </ul>
 */
@Component
public class AnalysisResultApplier {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultApplier.class);

    private static final Set<String> KINDS = Set.of("MISMATCH", "MISSING_INPUT", "MISSING_POLICY", "CONFLICT", "STALE_REF");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "BLOCKING");
    private static final Set<String> REASONS = Set.of("EVIDENCE_INSUFFICIENT", "UNSUPPORTED_CONTENT");

    private final ValidationRepository validations;
    private final ValidationResponses responses;

    public AnalysisResultApplier(ValidationRepository validations, ValidationResponses responses) {
        this.validations = validations;
        this.responses = responses;
    }

    /**
     * 성공 응답 반영.
     *
     * @param request 보낸 요청 (검사 키·승인 항목·관련 계획을 대조하는 데 쓴다)
     */
    @Transactional
    public void applySuccess(JobLease lease, Map<String, Object> request, Map<String, Object> response, long durationMs) {
        Optional<Current> current = current(lease);
        if (current.isEmpty()) {
            return;
        }
        ValidationRow v = current.get().validation();
        long runId = current.get().runId();
        String model = null;
        if (response.get("model") instanceof String m) {
            model = m;
        }

        String violation = contractViolation(request, response);
        if (violation != null) {
            fail(lease, v, runId, "ai_invalid_output", "ERROR", "출력 계약 위반: " + violation, "RETRY",
                    "같은 입력으로 다시 실행합니다 (VAL-A04)", model, durationMs);
            return;
        }
        Map<String, Object> result = map(response.get("result"));
        List<Map<String, Object>> unassessed = list(result.get("unassessed"));
        if (!unassessed.isEmpty()) {
            // PARTIAL: 모델은 돌았지만 일부 검사를 못 했다 → 사람이 근거·지원 범위를 보완한 뒤 새 검증
            Set<String> reasons = new LinkedHashSet<>();
            StringBuilder detail = new StringBuilder();
            for (Map<String, Object> u : unassessed) {
                reasons.add(String.valueOf(u.get("reason")));
                detail.append(u.get("check_key")).append(": ").append(u.get("message")).append("; ");
            }
            String code = "ai_analysis_incomplete";
            if (reasons.equals(Set.of("EVIDENCE_INSUFFICIENT"))) {
                code = "ai_evidence_insufficient";
            } else if (reasons.equals(Set.of("UNSUPPORTED_CONTENT"))) {
                code = "ai_unsupported_content";
            }
            fail(lease, v, runId, code, "ERROR", "의미 분석을 일부 수행하지 못했습니다", "HUMAN_REVIEW",
                    "근거·지원 범위를 보완한 뒤 새 검증을 요청하세요 — " + detail.toString().trim(), model, durationMs);
            return;
        }

        // COMPLETE: 후보 저장 + 규칙 판정
        boolean needsInput = false;
        boolean needsReview = false;
        for (Map<String, Object> f : list(result.get("findings"))) {
            String kind = String.valueOf(f.get("kind"));
            String severity = String.valueOf(f.get("severity"));
            String resolution = resolutionFor(kind);
            validations.insertFinding(v.id(), new FindingRow(null, v.id(), runId, "MODEL", kind, severity,
                    id(f.get("design_item_id")), id(f.get("design_item_version_id")), id(f.get("related_task_id")),
                    String.valueOf(f.get("message")), list(f.get("sources")), resolution, nextActionFor(kind, severity),
                    String.valueOf(f.get("check_key")), strings(f.get("evidence_ids"))));
            // 6절 판정표: 필수 누락(MISSING_INPUT·MISSING_POLICY)은 BLOCKING 이어야 한다(명세). 불일치·충돌·참조 문제는 BLOCKING 일 때만 막는다
            boolean blocking = "BLOCKING".equals(severity);
            if ("MISSING_INPUT".equals(kind) && blocking) {
                needsInput = true;
            }
            if (("MISSING_POLICY".equals(kind) && blocking)
                    || (blocking && ("MISMATCH".equals(kind) || "CONFLICT".equals(kind) || "STALE_REF".equals(kind)))) {
                needsReview = true;
            }
        }
        String status = "PASS";
        if (needsReview) {
            status = "NEEDS_REVIEW";          // 검토 필요와 보완이 함께면 검토 필요 우선 (6절)
        } else if (needsInput) {
            status = "NEEDS_INPUT";
        }
        Map<String, Object> summary = responses.summary(v.summary());
        summary.put("analysis_status", "COMPLETE");
        summary.put("checked_keys", result.get("checked_keys"));
        summary.put("unassessed", List.of());
        summary.put("model", model);
        summary.put("usage", list(response.get("usage")));   // 비용 기록(-168)까지 응답의 사용량을 버리지 않는다
        validations.finishRun(runId, "OK", null, model, durationMs);
        validations.finish(v.id(), status, summary);
        validations.finishJob(lease.jobId(), "DONE");
    }

    /** 호출 실패(시간 초과·연결·HTTP 오류·그 밖의 BE 오류) 반영 */
    @Transactional
    public void applyFailure(JobLease lease, String errorCode, String message, long durationMs) {
        Optional<Current> current = current(lease);
        if (current.isEmpty()) {
            return;
        }
        String runStatus = "ERROR";
        if ("ai_timeout".equals(errorCode)) {
            runStatus = "TIMEOUT";
        }
        fail(lease, current.get().validation(), current.get().runId(), errorCode, runStatus, message, "RETRY",
                "같은 입력으로 다시 실행합니다 (VAL-A04)", null, durationMs);
    }

    private void fail(JobLease lease, ValidationRow v, long runId, String errorCode, String runStatus, String message,
                      String resolution, String nextAction, String model, long durationMs) {
        validations.finishRun(runId, runStatus, errorCode + ": " + message, model, durationMs);
        validations.insertFinding(v.id(), FindingRow.error(runId, message, resolution, nextAction));
        Map<String, Object> summary = responses.summary(v.summary());
        summary.put(ValidationResponses.SUMMARY_ERROR_CODE, errorCode);
        validations.finish(v.id(), "ERROR", summary);
        validations.finishJob(lease.jobId(), "FAILED");
    }

    /** 잠그고 "아직 이 실행의 결과를 받을 상태인가". 아니면 늦은 결과로 처리하고 빈 값 */
    private Optional<Current> current(JobLease lease) {
        Optional<ValidationRow> found = validations.lock(lease.validationId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ValidationRow v = found.get();
        Optional<Map<String, Object>> run = validations.run(v.id(), lease.attempt());
        boolean sameLease = run.isPresent() && lease.leaseToken().toString().equals(String.valueOf(run.get().get("lease_token")));
        boolean live = "RUNNING".equals(v.status()) && v.currentAttempt() == lease.attempt() && sameLease
                && run.isPresent() && "RUNNING".equals(run.get().get("status"));
        if (!live) {
            // 늦은 결과: STALE·취소·새 시도·다른 리스. 실행이 아직 열려 있으면 DISCARDED 로 닫고 상태는 그대로
            if (run.isPresent() && "RUNNING".equals(run.get().get("status"))) {
                validations.finishRun(((Number) run.get().get("id")).longValue(), "DISCARDED", "늦은 결과", null, 0);
            }
            log.info("validation {} attempt {} result discarded (status={}, current_attempt={})", v.id(), lease.attempt(),
                    v.status(), v.currentAttempt());
            return Optional.empty();
        }
        return Optional.of(new Current(v, ((Number) run.get().get("id")).longValue()));
    }

    private record Current(ValidationRow validation, long runId) {
    }

    // ---- 응답 계약 검사 ----

    /** 위반이 있으면 그 설명, 없으면 null */
    String contractViolation(Map<String, Object> request, Map<String, Object> response) {
        if (!"MODEL".equals(response.get("origin"))) {
            return "origin 이 MODEL 이 아님";
        }
        if (!(response.get("model") instanceof String) || !(response.get("usage") instanceof List)) {
            return "model·usage 누락";
        }
        if (!(response.get("result") instanceof Map)) {
            return "result 누락";
        }
        Map<String, Object> result = map(response.get("result"));
        String analysis = String.valueOf(result.get("analysis_status"));
        if (!"COMPLETE".equals(analysis) && !"PARTIAL".equals(analysis)) {
            return "analysis_status 값 오류: " + analysis;
        }
        List<String> checked = strings(result.get("checked_keys"));
        List<Map<String, Object>> unassessed = list(result.get("unassessed"));
        Set<String> requested = new LinkedHashSet<>();
        for (Map<String, Object> c : list(request.get("checks"))) {
            requested.add(String.valueOf(c.get("key")));
        }
        Set<String> seen = new HashSet<>();
        for (String k : checked) {
            if (!requested.contains(k) || !seen.add(k)) {
                return "checked_keys 에 모르는 키 또는 중복: " + k;
            }
        }
        for (Map<String, Object> u : unassessed) {
            String k = String.valueOf(u.get("check_key"));
            if (!requested.contains(k) || !seen.add(k)) {
                return "unassessed 에 모르는 키 또는 중복: " + k;
            }
            if (!REASONS.contains(String.valueOf(u.get("reason")))) {
                return "unassessed.reason 값 오류: " + u.get("reason");
            }
        }
        if (!seen.equals(requested)) {
            return "검사 키가 빠짐: " + requested.stream().filter(k -> !seen.contains(k)).toList();
        }
        if ("COMPLETE".equals(analysis) != unassessed.isEmpty()) {
            return "analysis_status 와 unassessed 가 맞지 않음";
        }
        // 버전 → 항목. 항목과 버전이 짝이 맞아야 한다 (DDL FK (design_item_version_id, design_item_id))
        Map<String, String> itemOfVersion = new LinkedHashMap<>();
        for (Map<String, Object> item : list(request.get("approved_items"))) {
            itemOfVersion.put(String.valueOf(item.get("id")), String.valueOf(item.get("design_item_id")));
        }
        Set<String> relatedTasks = new HashSet<>();
        for (Map<String, Object> plan : list(request.get("related_plans"))) {
            relatedTasks.add(String.valueOf(plan.get("task_id")));
        }
        Set<String> evidenceKeys = new HashSet<>();
        for (Map<String, Object> e : list(request.get("evidence"))) {
            evidenceKeys.add(String.valueOf(e.get("evidence_id")));
        }
        for (Map<String, Object> f : list(result.get("findings"))) {
            if (!checked.contains(String.valueOf(f.get("check_key")))) {
                return "finding 의 check_key 가 checked_keys 에 없음: " + f.get("check_key");
            }
            if (!KINDS.contains(String.valueOf(f.get("kind")))) {
                return "finding.kind 값 오류: " + f.get("kind");
            }
            if (!SEVERITIES.contains(String.valueOf(f.get("severity")))) {
                return "finding.severity 값 오류: " + f.get("severity");
            }
            if (!(f.get("message") instanceof String msg) || msg.isBlank()) {
                return "finding.message 누락";
            }
            Object item = f.get("design_item_id");
            Object version = f.get("design_item_version_id");
            if ((item == null) != (version == null)) {
                return "design_item_id 와 design_item_version_id 는 함께 있어야 함";
            }
            if (item != null) {
                String expectedItem = itemOfVersion.get(String.valueOf(version));
                if (expectedItem == null || !expectedItem.equals(String.valueOf(item)) || !isNumber(item) || !isNumber(version)) {
                    return "입력에 없는 설계 항목·버전 참조: " + item + "/" + version;
                }
            }
            Object relatedTask = f.get("related_task_id");
            if (relatedTask != null && (!isNumber(relatedTask) || !relatedTasks.contains(String.valueOf(relatedTask)))) {
                return "입력에 없는 관련 작업 참조: " + relatedTask;
            }
            if (!(f.get("sources") instanceof List) || !(f.get("evidence_ids") instanceof List)) {
                return "sources·evidence_ids 누락";
            }
            for (String ev : strings(f.get("evidence_ids"))) {
                if (!evidenceKeys.contains(ev)) {
                    return "입력에 없는 근거 키: " + ev;
                }
            }
        }
        return null;
    }

    /** 후보 종류 → 후속 경로. 입력 보완은 FIX_PLAN, 정책 결정은 PROPOSE_CHANGE, 충돌·불일치는 사람 검토 */
    static String resolutionFor(String kind) {
        if ("MISSING_INPUT".equals(kind) || "STALE_REF".equals(kind)) {
            return "FIX_PLAN";
        }
        if ("MISSING_POLICY".equals(kind)) {
            return "PROPOSE_CHANGE";
        }
        return "HUMAN_REVIEW";
    }

    /** BLOCKING 이면 next_action 필수 (명세 Finding) */
    static String nextActionFor(String kind, String severity) {
        if (!"BLOCKING".equals(severity)) {
            return null;
        }
        if ("MISSING_INPUT".equals(kind) || "STALE_REF".equals(kind)) {
            return "계획을 보완해 새 리비전으로 다시 올리고 검증을 요청하세요";
        }
        if ("MISSING_POLICY".equals(kind)) {
            return "설계에 없는 정책입니다. 변경 제안으로 정책을 정한 뒤 다시 검증하세요";
        }
        return "팀장·관련 담당자와 검토한 뒤 계획을 고치거나 변경 제안을 올리세요";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static Long id(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    /** 십진 정수 문자열(또는 정수)인가. ID 는 문자열로 오지만 숫자로 와도 받는다 */
    private static boolean isNumber(Object value) {
        try {
            Long.parseLong(String.valueOf(value));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
