package com.beez.backend.validation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.CanonicalJson;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.validation.config.ValidationProperties;
import com.beez.backend.validation.dto.AcceptResult;
import com.beez.backend.validation.repository.FindingRow;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * VAL-A01·MCP-11 작업 검증 접수 (S15P31A108-159).
 * <ol>
 *   <li>권한: 프로젝트 멤버, 프로젝트 잠금 뒤 작업 담당자인지(TASK_OWNER)</li>
 *   <li>현재 기준 없음 409 baseline_missing. 계획 리비전·잠금 번호가 지금 값과 다르면 409 revision_conflict</li>
 *   <li>입력 고정: 계획의 설계 참조 → validation_design_ref, 같은 항목을 보는 열린 다른 작업의 현재 계획 → validation_task_ref,
 *       입력 해시 = 계획 리비전 + 참조 버전 + 관련 계획 + 규칙 버전 + 기준의 정규 JSON SHA-256</li>
 *   <li>규칙 검사({@link ValidationRules}). 확정되면 그 상태로 끝(200). 의미 분석이 필요하면 RUNNING·시도 1·실행(run)·작업 큐(job)를
 *       만들어 202. 워커({@code ValidationWorker})가 큐에서 꺼내 AI 를 부른다</li>
 *   <li>횟수 제한(-161): 의미 분석이 필요한데 같은 작업·같은 입력으로 이미 정한 횟수만큼 분석을 돌렸으면 409 validation_attempt_limit.
 *       재시도({@link ValidationRetryService})와 같은 수를 센다(실제 AI 를 부른 실행만). 서버가 접수하는 DELTA 검증의 첫 실행은 막지 않는다
 *       (DELTA 를 사람이 다시 돌리는 재시도는 센다)</li>
 * </ol>
 * 조회(VAL-A02)는 새 실행을 만들지 않는다. 판정은 규칙이 내리고 AI 는 발견 후보만 더한다 (공통 6절).
 *
 * <p>서버가 스스로 접수하는 경우({@link #acceptBySystem}): delta 가 있는 완료 보고의 DELTA 검증(S3)과, 설계 발행으로 DELTA 검증이
 * STALE 이 된 작업의 새 DELTA 검증(-163, {@link #reacceptAfterPublication}). 권한·잠금 번호 확인 없이, 호출자가 이미 프로젝트 행을
 * 잠근 트랜잭션 안에서 같은 입력 고정·규칙 검사·큐 넣기를 한다.
 */
@Service
public class ValidationAcceptService {

    private final ProjectAccessChecker access;
    private final ProjectDesignLookup designLookup;
    private final TaskRepository tasks;
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;
    private final ValidationRepository validations;
    private final ValidationRules rules;
    private final ValidationResponses responses;
    private final ObjectMapper json;
    private final ValidationProperties properties;
    private final ValidationActivities activities;

    public ValidationAcceptService(ProjectAccessChecker access, ProjectDesignLookup designLookup, TaskRepository tasks,
                                   TaskPlanRevisionRepository revisions, TaskPlanDesignRefRepository designRefs,
                                   ValidationRepository validations, ValidationRules rules, ValidationResponses responses,
                                   ObjectMapper json, ValidationProperties properties, ValidationActivities activities) {
        this.access = access;
        this.designLookup = designLookup;
        this.tasks = tasks;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.validations = validations;
        this.rules = rules;
        this.responses = responses;
        this.json = json;
        this.properties = properties;
        this.activities = activities;
    }

    @Transactional
    public AcceptResult accept(CallerContext caller, Long projectId, Long taskId, Long planRevisionId, int expectedLockVersion) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (planRevisionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "plan_revision_id 는 필수입니다");
        }
        // 프로젝트 잠금: 이 뒤에 읽는 작업·기준이 "지금" 값이다 (상태 보고와 같은 순서 — project 행 → task)
        if (!designLookup.lockProject(projectId)) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        Task task = tasks.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId));
        actor.requireMemberId(task.getAssigneeMemberId(), "담당자만 검증을 요청할 수 있습니다");
        if (task.getStatus().isClosed()) {
            // 완료·취소된 작업은 검증할 계획이 더 없다. 작업 취소는 모든 검증을 이미 무효화했다 (TSK-A07 오류표의 전이 오류 코드)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition", "완료·취소된 작업은 검증을 요청할 수 없습니다");
        }
        Long baselineId = designLookup.requireCurrentBaseline(projectId);
        if (!planRevisionId.equals(task.getCurrentPlanRevisionId()) || task.getLockVersion() != expectedLockVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "현재 계획·상태가 바뀌었습니다 (현재 plan_revision_id=" + task.getCurrentPlanRevisionId()
                            + ", lock_version=" + task.getLockVersion() + ")");
        }

        return accept(caller, projectId, task, planRevisionId, baselineId, "AGENT", null, false);
    }

    /**
     * 서버가 접수하는 검증 (trigger=DELTA). 호출자가 프로젝트 행을 잠근 트랜잭션 안에서만 부른다 — 여기서 읽는 작업·기준이
     * "지금" 값이어야 하고, 완료 보고 저장(S3)·설계 발행(S1)과 한 트랜잭션으로 묶여야 한다.
     * 담당자·잠금 번호는 확인하지 않는다(사람 요청이 아니다). 현재 기준이 없으면 409 baseline_missing 그대로.
     *
     * @return 접수 결과. DELTA 는 완료 delta 검사가 항상 들어가 규칙만으로는 PASS 가 나지 않는다 (NEEDS_INPUT·NEEDS_REVIEW 로는 끝날 수 있다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptResult acceptBySystem(CallerContext caller, Long projectId, Task task, String trigger, List<Map<String, Object>> completionDelta) {
        if (!"DELTA".equals(trigger)) {
            throw new IllegalArgumentException("서버 접수는 DELTA 만 지원합니다: " + trigger);
        }
        // 완료 보고한 사람의 요청 안에서 끝나면 그 사람이 검증 완료의 행위자다
        return acceptDelta(caller, projectId, task, designLookup.requireCurrentBaseline(projectId), completionDelta, false);
    }

    /**
     * 설계 발행으로 DELTA 검증이 STALE 이 된, delta 완료 보고로 기다리던 작업의 새 DELTA 검증(-163). 규칙만으로 끝나도 검증 완료
     * 활동의 행위자는 시스템이다 — 발행한 사람은 이 작업의 완료를 보고하지 않았다(발행에 딸려 서버가 다시 접수했다, PR #202 리뷰).
     * 워커 검색 권한({@code request_actor})은 여전히 발행한 사람이다.
     *
     * @param baselineId 발행 중인 새 기준. 발행 트랜잭션 안에서는 현재 포인터가 아직 바뀌기 전이라 직접 받는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptResult reacceptAfterPublication(CallerContext caller, Long projectId, Task task, Long baselineId,
                                                 List<Map<String, Object>> completionDelta) {
        return acceptDelta(caller, projectId, task, baselineId, completionDelta, true);
    }

    /**
     * @param completionDelta    이 DELTA 검증이 검사할 완료 보고의 delta. 분석 입력의 일부라 입력 스냅샷 해시에 들어간다
     *                           (같은 계획에 다른 delta 를 보고하면 다른 입력이다)
     * @param finishedBySystem   규칙만으로 끝났을 때 검증 완료 활동을 시스템 행위자로 남길지
     */
    private AcceptResult acceptDelta(CallerContext caller, Long projectId, Task task, Long baselineId,
                                     List<Map<String, Object>> completionDelta, boolean finishedBySystem) {
        // The server-triggered work delegates the authenticated operation's project access;
        // the worker rechecks it before searching. A missing or revoked actor fails closed.
        access.requireMember(caller, projectId);
        if (completionDelta == null || completionDelta.isEmpty()) {
            throw new IllegalArgumentException("DELTA 검증에는 완료 보고의 delta 가 있어야 합니다");
        }
        return accept(caller, projectId, task, task.getCurrentPlanRevisionId(), baselineId, "DELTA", completionDelta, finishedBySystem);
    }

    /**
     * 공통 접수: 입력 고정 → 규칙 검사 → 확정 또는 큐. 호출자가 권한·잠금·기준을 이미 확인했다. completionDelta 는 DELTA 에만 있다
     *
     * @param finishedBySystem 규칙만으로 끝났을 때 검증 완료 활동의 행위자를 시스템으로 (아니면 요청자)
     */
    private AcceptResult accept(CallerContext caller, Long projectId, Task task, Long planRevisionId, Long baselineId, String trigger,
                                List<Map<String, Object>> completionDelta, boolean finishedBySystem) {
        Long taskId = task.getId();
        TaskPlanRevision plan = revisions.findByIdAndTaskId(planRevisionId, taskId)
                .orElseThrow(() -> AccessErrors.notFound("plan_revision_missing revision=" + planRevisionId));
        List<TaskPlanDesignRef> refs = designRefs.findByPlanRevisionId(planRevisionId);
        List<Map<String, Object>> relatedOpen = validations.openTasksSharingItems(projectId, taskId, planRevisionId);

        boolean policyEnabled = properties.policyAnalysisEnabled();
        String ruleVersion = policyEnabled ? ValidationRules.POLICY_RULE_VERSION : ValidationRules.RULE_VERSION;
        RuleOutcome outcome = rules.evaluate(projectId, baselineId, plan, refs, relatedOpen, "DELTA".equals(trigger), policyEnabled);
        Map<Long, Long> relatedPlans = relatedPlans(relatedOpen);
        String inputHash = inputHash(plan, baselineId, refs, relatedPlanHashes(relatedOpen), trigger, completionDelta, ruleVersion);

        boolean finished = outcome.decidedStatus() != null;
        // Search and model versions are not pinned well enough to prove an AI result is reusable.
        // Re-run current rules under the project lock and reuse only a rules-only PASS for the
        // identical revision/baseline; DELTA and model-backed results always follow the normal path.
        if (!policyEnabled && "AGENT".equals(trigger) && "PASS".equals(outcome.decidedStatus())
                && outcome.findings().isEmpty() && outcome.checks().isEmpty()) {
            var previous = validations.reusableRulesOnlyPass(projectId, taskId, planRevisionId, baselineId, inputHash, ruleVersion);
            if (previous.isPresent()) {
                return new AcceptResult(responses.toResponse(previous.get(), true), true);
            }
        }
        if (!finished && "AGENT".equals(trigger)) {
            requireAttemptsLeft(validations, properties, taskId, inputHash, plan.getContextPacketId());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rule_version", ruleVersion);
        if (policyEnabled) summary.put("policy_analysis_version", ValidationRules.POLICY_CHECK);
        // Preserve the authenticated requester for later authorized background searches.
        // The public validation DTO does not expose this internal summary field.
        summary.put("request_actor", requestActor(caller));
        if (completionDelta != null) summary.put("completion_delta", completionDelta);
        long validationId;
        if (finished) {
            validationId = validations.insertValidation(taskId, planRevisionId, baselineId, outcome.decidedStatus(), trigger,
                    inputHash, ruleVersion, 0, summary, true);
        } else {
            summary.put("checks", outcome.checks());
            summary.put("attempt", 1);
            validationId = validations.insertValidation(taskId, planRevisionId, baselineId, "RUNNING", trigger,
                    inputHash, ruleVersion, 1, summary, false);
            // 시도 1 의 실행을 원문·검색 조회 전에 확보한다 (공통 "검증 실행 오류 규약"). 리스는 워커가 꺼낼 때 적는다
            validations.insertRun(validationId, 1, null, "RUNNING");
            validations.insertJob(validationId);
        }
        validations.copyDesignRefs(validationId, planRevisionId);
        for (Map.Entry<Long, Long> e : relatedPlans.entrySet()) {
            validations.insertTaskRef(validationId, e.getKey(), e.getValue());
        }
        for (FindingRow f : outcome.findings()) {
            validations.insertFinding(validationId, f);
        }
        ValidationRow row = validations.find(projectId, validationId).orElseThrow();
        if (finished && finishedBySystem) {
            activities.finishedBySystem(row, outcome.decidedStatus());
        } else if (finished) {
            // 요청 처리 안에서 규칙만으로 끝났다 — 행위자는 요청자 (계약 6.2 "검증 완료 정하기"). 워커가 끝내면 결과 반영이 남긴다
            activities.finishedBy(caller, row, outcome.decidedStatus());
        }
        return new AcceptResult(responses.toResponse(row), finished);
    }

    /**
     * 검증 요약에 남기는 요청자 (워커가 이 사람의 권한으로 검색한다). 공개 응답에는 나가지 않는다.
     * 재시도(VAL-A04)는 다시 돌리는 사람으로 바꿔 적는다
     */
    static Map<String, Object> requestActor(CallerContext caller) {
        Map<String, Object> requestActor = new LinkedHashMap<>();
        requestActor.put("account_id", caller.accountId());
        requestActor.put("agent_token_id", caller.agentTokenId());
        requestActor.put("kind", caller.kind().name());
        return requestActor;
    }

    /**
     * 횟수 제한 (-161). 같은 작업·같은 검증 입력(입력 스냅샷 해시와 계획의 컨텍스트 패킷)으로 실제 검증 AI 를 부른 실행이 이미 정한 횟수에
     * 닿았으면 409 validation_attempt_limit. 새 검증 요청(VAL-A01·MCP-11)과 재시도(VAL-A04·MCP-14)가 같은 수를 본다 — 새 검증으로 재시도
     * 제한을 돌아가지 못하게. 무엇을 세는지는 {@link ValidationRepository#countedModelAttempts} (가짜 분석기·AI 에 닿지 않은 실패는 세지 않는다)
     */
    static void requireAttemptsLeft(ValidationRepository validations, ValidationProperties properties, long taskId, String inputHash,
                                    Long contextPacketId) {
        int used = validations.countedModelAttempts(taskId, inputHash, contextPacketId, FakeValidationAnalyzer.MODEL);
        if (used >= properties.maxModelAttempts()) {
            throw new ApiException(HttpStatus.CONFLICT, "validation_attempt_limit",
                    "같은 계획으로 분석을 " + properties.maxModelAttempts() + "번 돌렸습니다. 계획을 고치거나 팀에 문의하세요");
        }
    }

    /** 관련 열린 작업 → 그 작업의 현재 계획 리비전 (작업·항목 쌍 행에서 작업마다 하나로) */
    private static Map<Long, Long> relatedPlans(List<Map<String, Object>> relatedOpen) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : relatedOpen) {
            out.put(((Number) row.get("task_id")).longValue(), ((Number) row.get("plan_revision_id")).longValue());
        }
        return out;
    }

    /** 관련 열린 작업 → 그 작업 현재 계획의 입력 해시 (해시 입력용. 리비전 번호가 아니라 내용으로 비교한다) */
    private static Map<Long, String> relatedPlanHashes(List<Map<String, Object>> relatedOpen) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : relatedOpen) {
            out.put(((Number) row.get("task_id")).longValue(), (String) row.get("plan_input_hash"));
        }
        return out;
    }

    /**
     * 입력 스냅샷 해시: 같은 계획 내용·참조·관련 계획 내용·규칙·기준·검증 종류·완료 delta 면 같은 값 (키 정렬 정규 JSON, 목록 순서 유지).
     * 계획은 리비전 번호가 아니라 계획 입력 해시(task_plan_revision.input_hash — 제목·접근 방식을 뺀 내용, AC-13)로 넣는다 —
     * 제목만 바꾼 새 리비전은 같은 입력이다 (-162). 관련 작업의 계획도 같은 이유로 그 계획의 입력 해시다.
     * 검증 종류(trigger)와 delta 가 들어가는 이유: 같은 계획이라도 AGENT 검증과 DELTA 검증은 검사 범위가 다르고, 같은 계획에 다른 delta 를
     * 보고하면 분석 입력이 다르다. 규칙 전용 PASS 재사용은 이 해시 외에도 작업·리비전·기준과 현재 규칙을 확인한다.
     * 횟수 제한(-161)도 이 해시로 센다
     */
    private String inputHash(TaskPlanRevision plan, Long baselineId, List<TaskPlanDesignRef> refs, Map<Long, String> relatedPlans,
                             String trigger, List<Map<String, Object>> completionDelta, String ruleVersion) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trigger", trigger);
        input.put("plan_input_hash", plan.getInputHash());
        if (ValidationRules.POLICY_RULE_VERSION.equals(ruleVersion)) {
            input.put("policy_plan_title", plan.getTitle());
            input.put("policy_plan_approach", plan.getApproach());
        }
        input.put("baseline_id", baselineId);
        if (completionDelta != null) {
            input.put("completion_delta", completionDelta);
        }
        // 설계 참조는 항목 번호 순으로 넣는다 — DB 가 돌려주는 순서는 정해져 있지 않아, 같은 참조라도 순서가 다르면 해시가 달라진다
        // (계획 입력 해시 PlanInputHasher 도 같은 이유로 정렬한다). 계획 하나에 항목마다 참조는 하나다.
        // Comparator.comparing: 각 참조의 항목 번호를 꺼내 그 값으로 비교하는 정렬 기준을 만든다
        List<TaskPlanDesignRef> sortedRefs = new ArrayList<>(refs);
        sortedRefs.sort(java.util.Comparator.comparing(TaskPlanDesignRef::getDesignItemId));
        List<Map<String, Object>> refList = new ArrayList<>();
        for (TaskPlanDesignRef ref : sortedRefs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("design_item_id", ref.getDesignItemId());
            r.put("design_item_version_id", ref.getDesignItemVersionId());
            r.put("relation", ref.getRelation().name());
            refList.add(r);
        }
        input.put("design_refs", refList);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map.Entry<Long, String> e : relatedPlans.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("task_id", e.getKey());
            r.put("plan_input_hash", e.getValue());
            related.add(r);
        }
        input.put("related_plans", related);
        input.put("rule_version", ruleVersion);
        return CanonicalJson.sha256(json, input);
    }
}
