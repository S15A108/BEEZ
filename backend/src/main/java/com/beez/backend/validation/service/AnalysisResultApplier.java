package com.beez.backend.validation.service;

import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
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
 *   <li>먼저 프로젝트 행을 잠그고(계획 변경·상태 보고와 같은 순서) 검증을 잠가 아직 RUNNING·같은 시도·같은 리스인지 본다.
 *       아니면 늦은 결과: 실행이 아직 RUNNING 이면 DISCARDED 로 닫고 검증 상태는 바꾸지 않는다. 프로젝트 행을 쥔 동안은
 *       같은 프로젝트의 계획 변경·상태 전이가 기다리므로, 입력 확인부터 결과 저장까지 관련 작업이 바뀔 수 없다</li>
 *   <li>응답 검사(필수 배열·원소 타입, 검사 키 분할, ID·출처·근거)를 통과한 COMPLETE 만 후보를 저장하고, 규칙 판정을 지금
 *       상태로 다시 내려(관련 작업의 상태만 바뀐 경우를 잡는다) 6절 판정표로 PASS·NEEDS_INPUT·NEEDS_REVIEW</li>
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
    private final ValidationRules rules;
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;

    public AnalysisResultApplier(ValidationRepository validations, ValidationResponses responses,
                                 ProjectDesignLookup designLookup, ValidationRules rules,
                                 TaskPlanRevisionRepository revisions, TaskPlanDesignRefRepository designRefs) {
        this.validations = validations;
        this.responses = responses;
        this.designLookup = designLookup;
        this.rules = rules;
        this.revisions = revisions;
        this.designRefs = designRefs;
    }

    /**
     * 성공 응답(HTTP 200) 반영.
     *
     * @param request  보낸 요청 (검사 키·승인 항목·관련 계획·출처·근거를 대조하는 데 쓴다)
     * @param response AI 응답 본문. 본문이 비어 있으면 null 로 올 수 있다
     */
    @Transactional
    public void applySuccess(JobLease lease, Map<String, Object> request, Map<String, Object> response, long durationMs) {
        // 리스가 만료된 뒤 도착한 응답이면 반영하지 않고 만료로 닫는다. 그때도 모델을 부른 사용량은 진단 값으로 남긴다
        Map<String, Object> lateDiagnostics = Map.of();
        if (response != null) {
            lateDiagnostics = diagnostics(response, String.valueOf(map(response.get("result")).get("analysis_status")));
        }
        Optional<Current> current = current(lease, Mode.RESULT, lateDiagnostics, durationMs);
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

        // COMPLETE: 규칙 판정을 지금 상태로 다시 내린다. 접수 뒤 결과를 기다리는 사이 관련 작업의 상태만 바뀐 경우
        // (같은 항목을 CHANGES 하는 작업이 READY → IN_PROGRESS 로 점유)는 입력 비교(작업·계획 리비전)로는 잡히지 않고
        // 충돌 규칙으로만 잡힌다. 설계 발행으로 참조 버전이 낡은 경우도 같이 잡힌다. 프로젝트 행을 쥐고 있으므로 지금 읽는
        // 상태가 저장 시점의 상태다. RUNNING 으로 접수된 검증은 접수 때 규칙 발견 항목이 없어(있었으면 그때 확정됐다) 겹치지 않는다
        RuleOutcome rule = reevaluateRules(v);
        boolean needsInput = "NEEDS_INPUT".equals(rule.decidedStatus());
        boolean needsReview = "NEEDS_REVIEW".equals(rule.decidedStatus());
        for (FindingRow f : rule.findings()) {
            validations.insertFinding(v.id(), f);
        }

        // 후보 저장. 출처는 AI 가 보낸 값 대신 요청에 넣은 출처 객체로 바꿔 저장한다
        // (계약 검사는 네 칸을 문자열로 비교하므로, 숫자 ID·명세에 없는 칸이 섞인 값을 그대로 내보내지 않게)
        Map<String, Map<String, Object>> requestSources = requestSources(request);
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
                // BE 가 현재 기준과 다시 대조해 정말 낡았으면 규칙 판정과 같이 NEEDS_INPUT (규칙 재판정이 그 항목의 규칙
                // 발견 항목을 이미 더했다), 아니면 사람 검토로 넘긴다
                if (isStale(v.projectId(), item, version)) {
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
                    id(f.get("related_task_id")), (String) f.get("message"), canonicalSources(f, requestSources), resolution,
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
        validations.finishRun(runId, "OK", null, model, durationMs, response.get("usage"));
        validations.finish(v.id(), status, summary);
        validations.finishJob(lease.jobId(), "DONE");
    }

    /**
     * 모델을 부르기 전 확인. 큐에서 기다리는 사이 관련 작업이 시작해 점유 충돌이 됐거나 참조 버전이 낡았으면 규칙만으로 판정이
     * 서므로, 버릴 결과를 위해 모델을 부르지 않고 여기서 닫는다 (접수 때 규칙으로 확정되는 것과 같은 결과). 실행은 모델을 부르지
     * 않았으니 DISCARDED, 작업은 DONE. 입력 자체가 바뀐 경우(STALE)와 늦은 결과는 {@link #current} 가 같은 방식으로 정리한다.
     *
     * @return 이 검증을 여기서 닫았으면(또는 이미 닫혀 있어 더 할 일이 없으면) true. false 면 아직 RUNNING 이고 모델을 불러야 한다
     */
    @Transactional
    public boolean closeByRulesIfDecided(JobLease lease) {
        Optional<Current> current = current(lease, Mode.RESULT, Map.of(), 0);
        if (current.isEmpty()) {
            return true;
        }
        ValidationRow v = current.get().validation();
        RuleOutcome rule = reevaluateRules(v);
        if (rule.decidedStatus() == null || "PASS".equals(rule.decidedStatus())) {
            return false;              // PASS 는 접수 때 확정됐을 것이므로 여기 오지 않는다. 의미 분석이 필요하면 모델을 부른다
        }
        for (FindingRow f : rule.findings()) {
            validations.insertFinding(v.id(), f);
        }
        validations.finishRun(current.get().runId(), "DISCARDED", "모델을 부르기 전에 규칙으로 판정이 확정됨 (" + rule.decidedStatus() + ")",
                null, 0);
        validations.finish(v.id(), rule.decidedStatus(), responses.summary(v.summary()));
        validations.finishJob(lease.jobId(), "DONE");
        log.info("validation {} attempt {} decided by rules before calling the model: {}", v.id(), lease.attempt(), rule.decidedStatus());
        return true;
    }

    /** 호출 실패(시간 초과·연결·HTTP 오류·그 밖의 BE 오류) 반영. 모델 응답이 없으니 진단 값도 없다 */
    @Transactional
    public void applyFailure(JobLease lease, String errorCode, String message, long durationMs) {
        applyFailure(lease, errorCode, message, durationMs, null);
    }

    /**
     * 호출 실패 반영. AI 가 모델을 부른 뒤 실패한 오류 응답(504 등)에 사용량을 넣어 줬으면 그 사용량을 실행에 남긴다
     * (AI-A02 오류 규약 "실패 호출 비용도 보존"). 늦은 결과·입력 변경으로 버릴 때도 같은 사용량을 남긴다
     *
     * @param usage 오류 응답의 usage 배열. 없으면 null
     */
    @Transactional
    public void applyFailure(JobLease lease, String errorCode, String message, long durationMs, List<Map<String, Object>> usage) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (validUsage(usage) != null) {
            diagnostics.put("usage", usage);
        }
        Optional<Current> current = current(lease, Mode.RESULT, diagnostics, durationMs);
        if (current.isEmpty()) {
            return;
        }
        String runStatus = "ERROR";
        if ("ai_timeout".equals(errorCode)) {
            runStatus = "TIMEOUT";
        }
        fail(lease, current.get().validation(), current.get().runId(), errorCode, runStatus, message, "RETRY",
                "같은 입력으로 다시 실행합니다 (VAL-A04)", null, durationMs, diagnostics);
    }

    /**
     * 리스 만료 회수 (공통 "검증 실행 오류 규약": 워커 중단·lease 만료 → 실행 ERROR, 검증 ERROR, RETRY).
     * 일반 반영과 같은 확인(아직 RUNNING·같은 시도·같은 리스·같은 계획)을 거친다. 이미 다른 경로가 종결했으면 아무것도 바꾸지 않고,
     * 이 리스의 작업이 남아 있으면 닫기만 한다. 회수 뒤에 예전 워커의 결과가 도착하면 검증이 ERROR 라 늦은 결과로 버려진다.
     */
    @Transactional
    public void applyLeaseExpired(JobLease lease) {
        Optional<Current> current = current(lease, Mode.RECOVERY, Map.of(), 0);
        if (current.isEmpty()) {
            return;
        }
        fail(lease, current.get().validation(), current.get().runId(), "validation_lease_expired", "ERROR",
                "분석 실행이 시간 안에 결과를 반영하지 못했습니다 (워커 중단·재배포)", "RETRY",
                "같은 계획으로 다시 검증을 요청하세요", null, 0, Map.of());
    }

    /**
     * @param diagnostics 모델을 부른 경우의 사용량·검사 키·부분 후보. summary 에 그대로 더한다 (빈 Map 이면 더할 것 없음)
     */
    private void fail(JobLease lease, ValidationRow v, long runId, String errorCode, String runStatus, String message,
                      String resolution, String nextAction, String model, long durationMs, Map<String, Object> diagnostics) {
        validations.finishRun(runId, runStatus, errorCode + ": " + message, model, durationMs, diagnostics.get("usage"));
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
        // 비용 기록(-168)에서 실패한 실행의 사용량도 빠지지 않게. 다만 모양이 틀린 값(배열이 아님 등)은 실행의 usage 칸이
        // 객체 배열만 받으므로 저장하지 않는다 — 저장이 실패하면 원래 오류 분류(ai_invalid_output)가 BE 오류로 바뀐다
        Object usage = validUsage(response.get("usage"));
        if (usage != null) {
            out.put("usage", usage);
        } else if (response.containsKey("usage")) {
            out.put("usage_rejected", String.valueOf(response.get("usage")));   // 진단용 원문 (문자열이라 어떤 모양이든 안전)
        }
        return out;
    }

    /** 객체 배열(명세 Usage[])이면 그대로, 아니면 null. 빈 배열은 "호출 없음" 이므로 그대로 둔다 */
    private static Object validUsage(Object usage) {
        if (usage == null || !isObjectList(usage)) {
            return null;
        }
        return usage;
    }

    /** 응답 본문의 usage 배열 (모양이 맞을 때만). 반영이 실패해 실패로 닫을 때 워커가 꺼내 쓴다 */
    public static List<Map<String, Object>> usageIn(Map<String, Object> response) {
        if (response == null || validUsage(response.get("usage")) == null) {
            return null;
        }
        return objects(response.get("usage"));
    }

    /** 결과 반영(워커가 받은 응답·실패)인지, 리스 만료 회수인지 */
    private enum Mode { RESULT, RECOVERY }

    /**
     * 잠그고 "아직 이 실행의 결과를 받을 상태인가". 아니면 늦은 결과로 처리하고 빈 값.
     * 결과 반영은 job 이 아직 이 리스로 LEASED 이고 <b>유효기간 안</b>이어야 받는다. 만료됐는데 회수가 아직 안 됐으면
     * 받지 않고 그 자리에서 만료로 닫는다(회수와 같은 결과). 회수는 반대로 만료된 리스만 닫는다.
     *
     * @param lateDiagnostics 결과를 받지 않고 닫을 때 summary 에 남길 진단 값 (모델을 부른 사용량 등). 모델을 안 불렀으면 빈 Map
     * @param durationMs      모델 호출에 걸린 시간. 버리는 실행에도 남긴다
     */
    private Optional<Current> current(JobLease lease, Mode mode, Map<String, Object> lateDiagnostics, long durationMs) {
        // 프로젝트 행을 먼저 잠근다 — 계획 변경(TSK-A04)·상태 보고·접수와 같은 project → task 순서. 이 트랜잭션이 끝날 때까지
        // 같은 프로젝트의 계획 변경·상태 전이·새 작업이 기다리므로, 아래 입력 확인과 결과 저장 사이에 관련 작업이 끼어들 수 없다
        Optional<Long> projectId = validations.projectIdOf(lease.validationId());
        if (projectId.isEmpty()) {
            return Optional.empty();
        }
        designLookup.lockProject(projectId.get());
        Optional<ValidationRow> found = validations.lock(lease.validationId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ValidationRow v = found.get();
        String model = null;
        if (lateDiagnostics.get("model") instanceof String m) {
            model = m;
        }
        Object usage = lateDiagnostics.get("usage");
        Optional<Map<String, Object>> run = validations.run(v.id(), lease.attempt());
        boolean sameLease = run.isPresent() && lease.leaseToken().toString().equals(String.valueOf(run.get().get("lease_token")));
        boolean live = "RUNNING".equals(v.status()) && v.currentAttempt() == lease.attempt() && sameLease
                && run.isPresent() && "RUNNING".equals(run.get().get("status"));
        if (!live) {
            // 늦은 결과: STALE·취소·새 시도·다른 리스. 실행이 아직 열려 있으면 DISCARDED 로 닫고 상태는 그대로.
            // 이미 닫힌 실행(리스 회수로 ERROR)이라도 모델을 부른 사용량은 그 실행에 남긴다 — 판정만 버린다
            if (run.isPresent()) {
                long lateRunId = ((Number) run.get().get("id")).longValue();
                if ("RUNNING".equals(run.get().get("status"))) {
                    validations.finishRun(lateRunId, "DISCARDED", "늦은 결과", model, durationMs, usage);
                } else if (usage != null) {
                    validations.recordLateUsage(lateRunId, usage);
                }
            }
            validations.cancelJobIfStillLeased(lease.jobId(), lease.leaseToken());
            log.info("validation {} attempt {} result discarded (status={}, current_attempt={})", v.id(), lease.attempt(),
                    v.status(), v.currentAttempt());
            return Optional.empty();
        }
        long runId = ((Number) run.get().get("id")).longValue();
        // 결과를 기다리는 사이 분석 입력(이 작업의 계획, 같은 항목을 보는 관련 작업과 그 계획)이 바뀌었으면 이 결과는 지난 입력의
        // 것이다. 진행 중 계획 변경(S2)은 끝난 검증만 STALE 로 바꾸고 도는 검증은 워커에 맡기므로, 여기서 버리고 STALE 로 닫는다.
        // 판정은 버려도 모델을 부른 사용량·모델은 실행과 요약에 남긴다 (비용 집계 -168)
        Optional<String> changed = inputChange(v);
        if (changed.isPresent()) {
            validations.finishRun(runId, "DISCARDED", "검증 입력이 바뀐 뒤 도착한 결과 (" + changed.get() + ")", model, durationMs, usage);
            validations.markStale(v.id(), changed.get(), usageOf(lateDiagnostics));
            validations.finishJob(lease.jobId(), "CANCELLED");
            log.info("validation {} attempt {} result discarded: input changed ({})", v.id(), lease.attempt(), changed.get());
            return Optional.empty();
        }
        // job 은 lock() 이 이미 잠갔다. 이 리스가 아직 job 을 쥐고 있는지, 유효기간이 지났는지 본다
        Optional<Map<String, Object>> job = validations.jobLease(lease.jobId());
        boolean ownsJob = job.isPresent() && "LEASED".equals(job.get().get("status"))
                && lease.leaseToken().toString().equals(String.valueOf(job.get().get("lease_token")));
        if (!ownsJob) {
            validations.finishRun(runId, "DISCARDED", "리스를 잃은 뒤 도착한 결과", model, durationMs, usage);
            log.info("validation {} attempt {} result discarded: job no longer leased by this worker", v.id(), lease.attempt());
            return Optional.empty();
        }
        boolean expired = Boolean.TRUE.equals(job.get().get("expired"));
        if (mode == Mode.RESULT && expired) {
            fail(lease, v, runId, "validation_lease_expired", "ERROR",
                    "분석 결과가 리스 유효기간이 지난 뒤 도착해 반영하지 않았습니다", "RETRY",
                    "같은 계획으로 다시 검증을 요청하세요", null, 0, lateDiagnostics);
            log.info("validation {} attempt {} result arrived after lease expired — closed as lease expired", v.id(), lease.attempt());
            return Optional.empty();
        }
        if (mode == Mode.RECOVERY && !expired) {
            return Optional.empty();                       // 아직 살아 있는 리스는 회수하지 않는다
        }
        return Optional.of(new Current(v, runId));
    }

    private record Current(ValidationRow validation, long runId) {
    }

    /** 진단 값 중 비용 집계에 필요한 것만 (모델·사용량). 버리는 결과의 후보는 남기지 않는다 */
    private static Map<String, Object> usageOf(Map<String, Object> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (diagnostics.containsKey("model")) {
            out.put("model", diagnostics.get("model"));
        }
        if (diagnostics.containsKey("usage")) {
            out.put("usage", diagnostics.get("usage"));
        }
        return out;
    }

    /**
     * 접수 때와 같은 규칙({@link ValidationRules})을 지금의 계획·참조·관련 열린 작업(상태 포함)으로 다시 돌린다.
     * 입력 비교({@link #inputChange})를 통과한 뒤이므로 계획·관련 작업 집합은 접수 때와 같고, 달라질 수 있는 것은 관련 작업의
     * 상태(CHANGES 점유 충돌)와 참조 버전의 최신성이다.
     */
    private RuleOutcome reevaluateRules(ValidationRow v) {
        TaskPlanRevision plan = revisions.findByIdAndTaskId(v.planRevisionId(), v.taskId()).orElseThrow();
        List<TaskPlanDesignRef> refs = designRefs.findByPlanRevisionId(v.planRevisionId());
        List<Map<String, Object>> relatedOpen = validations.openTasksSharingItems(v.projectId(), v.taskId(), v.planRevisionId());
        return rules.evaluate(v.projectId(), plan, refs, relatedOpen);
    }

    /**
     * 접수 때 고정한 분석 입력이 지금도 그대로인가. 바뀌었으면 무효화 사유:
     * <ul>
     *   <li>PLAN_CHANGED — 이 작업의 현재 계획 리비전이 검증한 리비전과 다름</li>
     *   <li>RELATED_PLAN_CHANGED — 같은 설계 항목을 보는 열린 다른 작업과 그 현재 계획(작업 → 계획 리비전)이 접수 때 고정한
     *       관련 작업(validation_task_ref)과 다름. 관련 작업의 계획이 바뀐 경우, 새 관련 작업이 생긴 경우, 관련 작업이 닫힌 경우 모두</li>
     * </ul>
     * 관련 작업의 상태만 바뀐 경우(점유 충돌)와 설계 발행으로 참조 버전이 낡은 경우는 여기가 아니라 결과 반영의 규칙 재판정
     * ({@link #reevaluateRules})이 잡는다. 설계 발행 수신(-163)은 끝난 검증을 STALE 로 바꾸고, 그 전까지는 시작 관문(G2)이 막는다.
     * 워커가 AI 를 부르기 전에도 같은 확인을 해 버릴 결과로 모델을 부르지 않는다.
     */
    public Optional<String> inputChange(ValidationRow v) {
        Optional<Long> currentPlan = validations.currentPlanRevision(v.taskId());
        if (currentPlan.isEmpty() || !currentPlan.get().equals(v.planRevisionId())) {
            return Optional.of("PLAN_CHANGED");
        }
        Map<Long, Long> fixed = new LinkedHashMap<>();
        for (Map<String, Object> row : validations.taskRefs(v.id())) {
            fixed.put(((Number) row.get("related_task_id")).longValue(), ((Number) row.get("related_plan_revision_id")).longValue());
        }
        Map<Long, Long> now = new LinkedHashMap<>();
        for (Map<String, Object> row : validations.openTasksSharingItems(v.projectId(), v.taskId(), v.planRevisionId())) {
            now.put(((Number) row.get("task_id")).longValue(), ((Number) row.get("plan_revision_id")).longValue());
        }
        if (!now.equals(fixed)) {
            return Optional.of("RELATED_PLAN_CHANGED");
        }
        return Optional.empty();
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
        for (Map<String, Object> item : objects(request.get("approved_items"))) {
            itemOfVersion.put(String.valueOf(item.get("id")), String.valueOf(item.get("design_item_id")));
        }
        Set<String> allowedSources = requestSources(request).keySet();
        Map<String, String> sourceOfEvidence = new LinkedHashMap<>();
        for (Map<String, Object> e : objects(request.get("evidence"))) {
            sourceOfEvidence.put(String.valueOf(e.get("evidence_id")), sourceKey(map(e.get("source"))));
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

    /** 요청에 넣은 출처 전체 (승인 항목·규칙 finding·근거). 비교 키 → 요청에 넣은 출처 객체 */
    private static Map<String, Map<String, Object>> requestSources(Map<String, Object> request) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> item : objects(request.get("approved_items"))) {
            if (item.get("source") instanceof Map) {
                Map<String, Object> s = map(item.get("source"));
                out.put(sourceKey(s), s);
            }
        }
        for (Map<String, Object> ruleFinding : objects(request.get("rule_findings"))) {
            for (Map<String, Object> s : objects(ruleFinding.get("sources"))) {
                out.put(sourceKey(s), s);
            }
        }
        for (Map<String, Object> e : objects(request.get("evidence"))) {
            Map<String, Object> s = map(e.get("source"));
            out.put(sourceKey(s), s);
        }
        return out;
    }

    /** 후보의 출처를 요청에 넣은 출처 객체로 바꾼다. 계약 검사를 통과했으므로 모두 찾을 수 있다 */
    private static List<Map<String, Object>> canonicalSources(Map<String, Object> finding,
                                                              Map<String, Map<String, Object>> requestSources) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : objects(finding.get("sources"))) {
            out.add(requestSources.get(sourceKey(s)));
        }
        return out;
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
