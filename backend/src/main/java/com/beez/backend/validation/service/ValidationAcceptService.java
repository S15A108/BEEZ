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
 * </ol>
 * 조회(VAL-A02)는 새 실행을 만들지 않는다. 판정은 규칙이 내리고 AI 는 발견 후보만 더한다 (공통 6절).
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

    public ValidationAcceptService(ProjectAccessChecker access, ProjectDesignLookup designLookup, TaskRepository tasks,
                                   TaskPlanRevisionRepository revisions, TaskPlanDesignRefRepository designRefs,
                                   ValidationRepository validations, ValidationRules rules, ValidationResponses responses,
                                   ObjectMapper json) {
        this.access = access;
        this.designLookup = designLookup;
        this.tasks = tasks;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.validations = validations;
        this.rules = rules;
        this.responses = responses;
        this.json = json;
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

        TaskPlanRevision plan = revisions.findByIdAndTaskId(planRevisionId, taskId)
                .orElseThrow(() -> AccessErrors.notFound("plan_revision_missing revision=" + planRevisionId));
        List<TaskPlanDesignRef> refs = designRefs.findByPlanRevisionId(planRevisionId);
        List<Map<String, Object>> relatedOpen = validations.openTasksSharingItems(projectId, taskId, planRevisionId);

        RuleOutcome outcome = rules.evaluate(projectId, plan, refs, relatedOpen);
        Map<Long, Long> relatedPlans = relatedPlans(relatedOpen);
        String inputHash = inputHash(planRevisionId, baselineId, refs, relatedPlans);

        boolean finished = outcome.decidedStatus() != null;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rule_version", ValidationRules.RULE_VERSION);
        long validationId;
        if (finished) {
            validationId = validations.insertValidation(taskId, planRevisionId, baselineId, outcome.decidedStatus(), "AGENT",
                    inputHash, ValidationRules.RULE_VERSION, 0, summary, true);
        } else {
            summary.put("checks", outcome.checks());
            summary.put("attempt", 1);
            validationId = validations.insertValidation(taskId, planRevisionId, baselineId, "RUNNING", "AGENT",
                    inputHash, ValidationRules.RULE_VERSION, 1, summary, false);
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
        return new AcceptResult(responses.toResponse(row), finished);
    }

    /** 관련 열린 작업 → 그 작업의 현재 계획 리비전 (작업·항목 쌍 행에서 작업마다 하나로) */
    private static Map<Long, Long> relatedPlans(List<Map<String, Object>> relatedOpen) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : relatedOpen) {
            out.put(((Number) row.get("task_id")).longValue(), ((Number) row.get("plan_revision_id")).longValue());
        }
        return out;
    }

    /** 입력 스냅샷 해시: 같은 계획·참조·관련 계획·규칙·기준이면 같은 값 (키 정렬 정규 JSON, 목록 순서 유지) */
    private String inputHash(Long planRevisionId, Long baselineId, List<TaskPlanDesignRef> refs, Map<Long, Long> relatedPlans) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("plan_revision_id", planRevisionId);
        input.put("baseline_id", baselineId);
        List<Map<String, Object>> refList = new ArrayList<>();
        for (TaskPlanDesignRef ref : refs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("design_item_id", ref.getDesignItemId());
            r.put("design_item_version_id", ref.getDesignItemVersionId());
            r.put("relation", ref.getRelation().name());
            refList.add(r);
        }
        input.put("design_refs", refList);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map.Entry<Long, Long> e : relatedPlans.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("task_id", e.getKey());
            r.put("plan_revision_id", e.getValue());
            related.add(r);
        }
        input.put("related_plans", related);
        input.put("rule_version", ValidationRules.RULE_VERSION);
        return CanonicalJson.sha256(json, input);
    }
}
