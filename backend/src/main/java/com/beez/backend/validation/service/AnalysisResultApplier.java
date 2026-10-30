package com.beez.backend.validation.service;

import com.beez.backend.task.service.ProjectDesignLookup;
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
 *   <li>응답 검사(필수 배열·원소 타입, 검사 키 분할, ID·출처·근거)를 통과한 COMPLETE 만 후보를 저장하고 6절 판정표로
 *       PASS·NEEDS_INPUT·NEEDS_REVIEW</li>
 *   <li>PARTIAL·계약 위반·호출 실패는 실행 종결 + ERROR 발견 항목(MODEL·ERROR·BLOCKING) + 검증 ERROR + 작업 FAILED 를 함께.
 *       모델을 실제로 부른 경우(PARTIAL·계약 위반)는 사용량·검사 키·부분 후보를 summary 에 진단용으로 남긴다</li>
 * </ul>
 */
@Component
public class AnalysisResultApplier {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultApplier.class);

    private static final Set<String> KINDS = Set.of("MISMATCH", "MISSING_INPUT", "MISSING_POLICY", "CONFLICT", "STALE_REF");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "BLOCKING");
    private static final Set<String> REASONS = Set.of("EVIDENCE_INSUFFICIENT", "UNSUPPORTED_CONTENT");
    /** 필수 누락 후보. 명세상 BLOCKING 으로만 온다 — 아니면 필수 누락이 있는 계획이 PASS 될 수 있어 계약 위반으로 본다 */
    private static final Set<String> BLOCKING_ONLY_KINDS = Set.of("MISSING_INPUT", "MISSING_POLICY");

    private final ValidationRepository validations;
    private final ValidationResponses responses;
    private final ProjectDesignLookup designLookup;

    public AnalysisResultApplier(ValidationRepository validations, ValidationResponses responses,
                                 ProjectDesignLookup designLookup) {
        this.validations = validations;
        this.responses = responses;
        this.designLookup = designLookup;
    }

    /**
     * 성공 응답(HTTP 200) 반영.
     *
     * @param request  보낸 요청 (검사 키·승인 항목·관련 계획·출처·근거를 대조하는 데 쓴다)
     * @param response AI 응답 본문. 본문이 비어 있으면 null 로 올 수 있다
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
        if (response != null && response.get("model") instanceof String m) {
            model = m;
        }

        String violation = contractViolation(request, response);
        if (violation != null) {
            fail(lease, v, runId, "ai_invalid_output", "ERROR", "출력 계약 위반: " + violation, "RETRY",
                    "같은 입력으로 다시 실행합니다 (VAL-A04)", model, durationMs, diagnostics(response, "INVALID"));
            return;
        }
        Map<String, Object> result = map(response.get("result"));
        List<Map<String, Object>> unassessed = objects(result.get("unassessed"));
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
                    "근거·지원 범위를 보완한 뒤 새 검증을 요청하세요 — " + detail.toString().trim(), model, durationMs,
                    diagnostics(response, "PARTIAL"));
            return;
        }

        // COMPLETE: 후보 저장 + 규칙 판정
        boolean needsInput = false;
        boolean needsReview = false;
        for (Map<String, Object> f : objects(result.get("findings"))) {
            String kind = (String) f.get("kind");
            String severity = (String) f.get("severity");
            boolean blocking = "BLOCKING".equals(severity);
            Long item = id(f.get("design_item_id"));
            Long version = id(f.get("design_item_version_id"));
            String resolution = resolutionFor(kind);
            String nextAction = nextActionFor(kind, severity);

            if ("STALE_REF".equals(kind)) {
                // AI 의 STALE_REF 는 "최신성을 다시 확인하라"는 신호다. 추론만으로 확정하지 않는다 (AI-A02 처리 규칙).
                // BE 가 현재 기준과 다시 대조해 정말 낡았으면 규칙 판정과 같이 NEEDS_INPUT, 아니면 사람 검토로 넘긴다
                if (isStale(v.projectId(), item, version)) {
                    validations.insertFinding(v.id(), FindingRow.rule("STALE_REF", "BLOCKING", item, version, null,
                            "참조한 항목 버전이 현재 기준의 버전과 다릅니다 (의미 분석 뒤 다시 확인)", "FIX_PLAN",
                            "현재 기준 버전으로 계획을 다시 올리세요"));
                    needsInput = true;
                } else {
                    resolution = "HUMAN_REVIEW";
                    if (blocking) {
                        nextAction = "참조가 낡았다는 분석 후보지만 현재 기준과 대조하면 최신입니다. 내용을 검토한 뒤 계획을 고치거나 그대로 진행하세요";
                        needsReview = true;
                    }
                }
            } else if ("MISSING_INPUT".equals(kind)) {
                needsInput = true;               // 계약 검사에서 BLOCKING 만 통과시켰다
            } else if ("MISSING_POLICY".equals(kind)) {
                needsReview = true;              // 계약 검사에서 BLOCKING 만 통과시켰다
            } else if (blocking) {
                needsReview = true;              // MISMATCH·CONFLICT 는 BLOCKING 일 때만 막는다
            }
            validations.insertFinding(v.id(), new FindingRow(null, v.id(), runId, "MODEL", kind, severity, item, version,
                    id(f.get("related_task_id")), (String) f.get("message"), objects(f.get("sources")), resolution,
                    nextAction, (String) f.get("check_key"), strings(f.get("evidence_ids"))));
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
        summary.put("usage", response.get("usage"));   // 비용 기록(-168)까지 응답의 사용량을 버리지 않는다
        validations.finishRun(runId, "OK", null, model, durationMs);
        validations.finish(v.id(), status, summary);
        validations.finishJob(lease.jobId(), "DONE");
    }

    /** 호출 실패(시간 초과·연결·HTTP 오류·그 밖의 BE 오류) 반영. 모델 응답이 없으니 진단 값도 없다 */
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
                "같은 입력으로 다시 실행합니다 (VAL-A04)", null, durationMs, Map.of());
    }

    /**
     * @param diagnostics 모델을 부른 경우의 사용량·검사 키·부분 후보. summary 에 그대로 더한다 (빈 Map 이면 더할 것 없음)
     */
    private void fail(JobLease lease, ValidationRow v, long runId, String errorCode, String runStatus, String message,
                      String resolution, String nextAction, String model, long durationMs, Map<String, Object> diagnostics) {
        validations.finishRun(runId, runStatus, errorCode + ": " + message, model, durationMs);
        validations.insertFinding(v.id(), FindingRow.error(runId, message, resolution, nextAction));
        Map<String, Object> summary = responses.summary(v.summary());
        summary.putAll(diagnostics);
        summary.put(ValidationResponses.SUMMARY_ERROR_CODE, errorCode);
        validations.finish(v.id(), "ERROR", summary);
        validations.finishJob(lease.jobId(), "FAILED");
    }

    /**
     * 모델을 실제로 부른 실패(PARTIAL·계약 위반)에서 남길 진단 값. 부분 후보는 정상 결과로 반영하지 않고 여기에만 둔다
     * (AI-A02 "부분 AI 후보는 진단 이력에만"). 계약 위반이면 값 모양을 믿을 수 없으니 받은 그대로 둔다.
     */
    private static Map<String, Object> diagnostics(Map<String, Object> response, String analysisStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null) {
            return out;
        }
        Map<String, Object> result = map(response.get("result"));
        out.put("analysis_status", analysisStatus);
        out.put("checked_keys", result.get("checked_keys"));
        out.put("unassessed", result.get("unassessed"));
        out.put("partial_findings", result.get("findings"));
        out.put("model", response.get("model"));
        out.put("usage", response.get("usage"));      // 비용 기록(-168)에서 실패한 실행의 사용량도 빠지지 않게
        return out;
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

    /** 항목·버전이 지금 기준에서 낡았는가. 항목이 없는 후보는 대조할 수 없으니 낡았다고 보지 않는다 */
    private boolean isStale(long projectId, Long item, Long version) {
        if (item == null || version == null) {
            return false;
        }
        Optional<Long> current = designLookup.currentVersionIdOf(projectId, item);
        return current.isEmpty() || !current.get().equals(version);
    }

    // ---- 응답 계약 검사 ----

    /**
     * 위반이 있으면 그 설명, 없으면 null. 필수 칸이 빠졌거나 타입이 틀리면 빈 값으로 넘기지 않고 위반으로 본다
     * (빈 findings 가 PASS 로 가는 것을 막는다).
     */
    String contractViolation(Map<String, Object> request, Map<String, Object> response) {
        if (response == null) {
            return "응답 본문이 없음";
        }
        if (!"MODEL".equals(response.get("origin"))) {
            return "origin 이 MODEL 이 아님";
        }
        if (!isText(response.get("model"))) {
            return "model 누락";
        }
        if (!isObjectList(response.get("usage"))) {
            return "usage 가 객체 배열이 아님";
        }
        if (!(response.get("result") instanceof Map)) {
            return "result 누락";
        }
        Map<String, Object> result = map(response.get("result"));
        String analysis = String.valueOf(result.get("analysis_status"));
        if (!"COMPLETE".equals(analysis) && !"PARTIAL".equals(analysis)) {
            return "analysis_status 값 오류: " + analysis;
        }
        if (!isTextList(result.get("checked_keys"))) {
            return "checked_keys 가 문자열 배열이 아님";
        }
        if (!isObjectList(result.get("unassessed"))) {
            return "unassessed 가 객체 배열이 아님";
        }
        if (!isObjectList(result.get("findings"))) {
            return "findings 가 객체 배열이 아님";
        }
        List<String> checked = strings(result.get("checked_keys"));
        List<Map<String, Object>> unassessed = objects(result.get("unassessed"));

        // 검사 키 분할: 요청 checks 의 키가 checked_keys·unassessed 에 빠짐·중복 없이 한 번씩
        Set<String> requested = new LinkedHashSet<>();
        for (Map<String, Object> c : objects(request.get("checks"))) {
            requested.add(String.valueOf(c.get("key")));
        }
        Set<String> seen = new HashSet<>();
        for (String k : checked) {
            if (!requested.contains(k) || !seen.add(k)) {
                return "checked_keys 에 모르는 키 또는 중복: " + k;
            }
        }
        for (Map<String, Object> u : unassessed) {
            if (!isText(u.get("check_key"))) {
                return "unassessed.check_key 누락";
            }
            String k = (String) u.get("check_key");
            if (!requested.contains(k) || !seen.add(k)) {
                return "unassessed 에 모르는 키 또는 중복: " + k;
            }
            if (!REASONS.contains(String.valueOf(u.get("reason")))) {
                return "unassessed.reason 값 오류: " + u.get("reason");
            }
        }
        if (!seen.equals(requested)) {
            List<String> missing = new ArrayList<>();
            for (String k : requested) {
                if (!seen.contains(k)) {
                    missing.add(k);
                }
            }
            return "검사 키가 빠짐: " + missing;
        }
        if ("COMPLETE".equals(analysis) != unassessed.isEmpty()) {
            return "analysis_status 와 unassessed 가 맞지 않음";
        }

        // 입력 집합: 항목 버전 → 항목, 관련 작업, 근거 키 → 근거 출처, 요청에 넣은 출처 전체
        Map<String, String> itemOfVersion = new LinkedHashMap<>();
        Set<String> allowedSources = new HashSet<>();
        for (Map<String, Object> item : objects(request.get("approved_items"))) {
            itemOfVersion.put(String.valueOf(item.get("id")), String.valueOf(item.get("design_item_id")));
            if (item.get("source") instanceof Map) {
                allowedSources.add(sourceKey(map(item.get("source"))));
            }
        }
        for (Map<String, Object> ruleFinding : objects(request.get("rule_findings"))) {
            for (Map<String, Object> s : objects(ruleFinding.get("sources"))) {
                allowedSources.add(sourceKey(s));
            }
        }
        Map<String, String> sourceOfEvidence = new LinkedHashMap<>();
        for (Map<String, Object> e : objects(request.get("evidence"))) {
            String key = sourceKey(map(e.get("source")));
            sourceOfEvidence.put(String.valueOf(e.get("evidence_id")), key);
            allowedSources.add(key);
        }
        Set<String> relatedTasks = new HashSet<>();
        for (Map<String, Object> plan : objects(request.get("related_plans"))) {
            relatedTasks.add(String.valueOf(plan.get("task_id")));
        }

        for (Map<String, Object> f : objects(result.get("findings"))) {
            if (!isText(f.get("check_key")) || !checked.contains((String) f.get("check_key"))) {
                return "finding 의 check_key 가 checked_keys 에 없음: " + f.get("check_key");
            }
            String kind = String.valueOf(f.get("kind"));
            String severity = String.valueOf(f.get("severity"));
            if (!KINDS.contains(kind)) {
                return "finding.kind 값 오류: " + kind;
            }
            if (!SEVERITIES.contains(severity)) {
                return "finding.severity 값 오류: " + severity;
            }
            if (BLOCKING_ONLY_KINDS.contains(kind) && !"BLOCKING".equals(severity)) {
                return kind + " 는 BLOCKING 이어야 함: " + severity;
            }
            if (!isText(f.get("message"))) {
                return "finding.message 누락";
            }
            if (!isText(f.get("suggested_action"))) {
                return "finding.suggested_action 누락";
            }
            Object item = f.get("design_item_id");
            Object version = f.get("design_item_version_id");
            if ((item == null) != (version == null)) {
                return "design_item_id 와 design_item_version_id 는 함께 있어야 함";
            }
            if (item != null) {
                if (!isNumber(item) || !isNumber(version)) {
                    return "설계 항목·버전 ID 형식 오류: " + item + "/" + version;
                }
                String expectedItem = itemOfVersion.get(String.valueOf(version));
                if (expectedItem == null || !expectedItem.equals(String.valueOf(item))) {
                    return "입력에 없는 설계 항목·버전 참조: " + item + "/" + version;
                }
            }
            Object relatedTask = f.get("related_task_id");
            if (relatedTask != null && (!isNumber(relatedTask) || !relatedTasks.contains(String.valueOf(relatedTask)))) {
                return "입력에 없는 관련 작업 참조: " + relatedTask;
            }

            // 출처: 각 출처가 요청에 넣은 출처(승인 항목·규칙 finding·근거) 중 하나여야 한다
            if (!isObjectList(f.get("sources"))) {
                return "finding.sources 가 객체 배열이 아님";
            }
            Set<String> findingSources = new HashSet<>();
            for (Map<String, Object> s : objects(f.get("sources"))) {
                if (!isSource(s)) {
                    return "finding.sources 원소 형식 오류: " + s;
                }
                String key = sourceKey(s);
                if (!allowedSources.contains(key)) {
                    return "입력에 없는 출처: " + s;
                }
                findingSources.add(key);
            }
            // 근거 키: 요청 evidence 에 있어야 하고, 그 근거의 출처가 sources 에 함께 있어야 한다
            if (!isTextList(f.get("evidence_ids"))) {
                return "finding.evidence_ids 가 문자열 배열이 아님";
            }
            for (String ev : strings(f.get("evidence_ids"))) {
                if (!sourceOfEvidence.containsKey(ev)) {
                    return "입력에 없는 근거 키: " + ev;
                }
                if (!findingSources.contains(sourceOfEvidence.get(ev))) {
                    return "근거 " + ev + " 의 출처가 sources 에 없음";
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

    // ---- 값 모양 검사 ----

    private static boolean isText(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    /** 배열이고 원소가 모두 객체인가. 빈 배열은 통과 */
    private static boolean isObjectList(Object value) {
        if (!(value instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (!(o instanceof Map)) {
                return false;
            }
        }
        return true;
    }

    /** 배열이고 원소가 모두 비어 있지 않은 문자열인가. 빈 배열은 통과 */
    private static boolean isTextList(Object value) {
        if (!(value instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (!isText(o)) {
                return false;
            }
        }
        return true;
    }

    /** 명세 Source 의 네 칸이 모두 있는가 */
    private static boolean isSource(Map<String, Object> s) {
        return isNumber(s.get("document_id")) && isNumber(s.get("document_version_id")) && isNumber(s.get("version_no"))
                && s.get("location") instanceof String;
    }

    /** 출처 비교용 키. 네 칸을 문자열로 이어 붙인다 (ID 가 숫자로 와도 문자열로 와도 같게) */
    private static String sourceKey(Map<String, Object> s) {
        return s.get("document_id") + "|" + s.get("document_version_id") + "|" + s.get("version_no") + "|" + s.get("location");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /** 객체 배열을 꺼낸다. 모양 검사(isObjectList)를 통과한 값에만 쓴다 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
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
        if (value == null) {
            return false;
        }
        try {
            Long.parseLong(String.valueOf(value));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
