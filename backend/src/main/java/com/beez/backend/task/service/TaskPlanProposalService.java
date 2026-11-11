package com.beez.backend.task.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.*;
import com.beez.backend.task.repository.TaskPlanProposalRepository;
import com.beez.backend.validation.dto.FindingResponse;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.service.ValidationAcceptService;
import com.beez.backend.validation.service.ValidationResponses;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Generation is outside a transaction. Applying the reviewed snapshot and accepting validation is one transaction. */
@Service
public class TaskPlanProposalService {
    private static final Set<String> FIELDS = Set.of("goal", "included_scope", "excluded_scope", "approach", "completion_criteria");
    private final ProjectAccessChecker access;
    private final ProjectDesignLookup design;
    private final TaskQueryService query;
    private final TaskPlanDraftService drafts;
    private final TaskPlanService plans;
    private final TaskPlanProposalRepository proposals;
    private final ValidationRepository validations;
    private final ValidationResponses responses;
    private final ValidationAcceptService accepts;
    private final ObjectMapper json;

    public TaskPlanProposalService(ProjectAccessChecker access, ProjectDesignLookup design, TaskQueryService query,
            TaskPlanDraftService drafts, TaskPlanService plans, TaskPlanProposalRepository proposals,
            ValidationRepository validations, ValidationResponses responses, ValidationAcceptService accepts, ObjectMapper json) {
        this.access = access; this.design = design; this.query = query; this.drafts = drafts; this.plans = plans;
        this.proposals = proposals; this.validations = validations; this.responses = responses; this.accepts = accepts; this.json = json;
    }

    public TaskPlanProposalResponse propose(CallerContext caller, long projectId, long taskId) {
        var task = ownedTask(caller, projectId, taskId);
        var validation = requireFixable(task);
        long baseline = design.requireCurrentBaseline(projectId);
        var source = validations.find(projectId, Long.parseLong(validation.id())).orElseThrow(() -> stale());
        var before = task.currentPlanRevision().plan();
        var findings = validation.findings().stream().filter(TaskPlanProposalService::fixable).toList();
        String memo = before.goal() + " " + findings.stream().map(FindingResponse::message).reduce("", (a, b) -> a + " " + b);
        if (memo.length() > 2000) memo = memo.substring(0, 2000);
        var generated = drafts.revision(caller, projectId, new TaskPlanDraftRequest(before.title(), memo), asMap(before),
                Map.of("id", validation.id(), "status", validation.status(), "findings", findings.stream().map(this::asMap).toList()));
        if ("INSUFFICIENT".equals(generated.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "insufficient_documents", "문서 근거만으로 수정안을 만들지 못했어요. 근거를 확인한 뒤 다시 시도해 주세요.");
        }
        if (!"PROPOSED".equals(generated.get("status"))) throw invalidOutput();
        var proposed = merge(before, generated.get("plan"));
        var changes = verifiedChanges(before, proposed, findings, generated.get("changes"));
        var evidence = objects(generated.get("evidence"));
        // Citation verification is performed by the shared draft service against stored original chunks.
        for (var change : changes) {
            if (evidence.stream().noneMatch(e -> change.get("field").equals(e.get("field")))) throw invalidOutput();
        }
        var after = ownedTask(caller, projectId, taskId);
        if (!Objects.equals(task.lockVersion(), after.lockVersion())
                || !task.currentPlanRevision().id().equals(after.currentPlanRevision().id())
                || !Objects.equals(validation, after.validation())
                || source.lockVersion() != validations.find(projectId, source.id()).orElseThrow(() -> stale()).lockVersion()
                || baseline != design.requireCurrentBaseline(projectId)
                || !Long.toString(baseline).equals(generated.get("baseline_id"))) throw stale();
        return proposals.insert(projectId, taskId, caller.accountId(), Long.parseLong(task.currentPlanRevision().id()),
                source.lockVersion(), new TaskPlanProposalResponse(null, Long.toString(baseline), validation.id(),
                        task.currentPlanRevision().revisionNo(), task.lockVersion(), before, proposed, changes, evidence,
                        Instant.now().plus(30, ChronoUnit.MINUTES)));
    }

    @Transactional
    public TaskPlanProposalApplyResponse apply(CallerContext caller, long projectId, long taskId, long proposalId) {
        // Same project -> task -> validation ordering as ordinary plan writes and the validation worker.
        access.requireMember(caller, projectId).requireHuman();
        if (!design.lockProject(projectId)) throw AccessErrors.notFound("project_missing");
        var task = ownedTask(caller, projectId, taskId);
        var stored = proposals.lock(projectId, taskId, proposalId)
                .orElseThrow(() -> AccessErrors.notFound("plan_proposal_missing"));
        if (stored.accountId() != caller.accountId()) throw AccessErrors.forbidden("proposal_owner", "수정안을 요청한 담당자만 승인할 수 있어요.");
        if (stored.appliedValidationId() != null) {
            var applied = validations.find(projectId, stored.appliedValidationId()).orElseThrow();
            return new TaskPlanProposalApplyResponse(task, responses.toResponse(applied));
        }
        var proposal = stored.content();
        var source = validations.find(projectId, Long.parseLong(proposal.sourceValidationId())).orElseThrow(() -> stale());
        if (!Instant.now().isBefore(proposal.expiresAt())
                || !proposal.baselineId().equals(Long.toString(design.requireCurrentBaseline(projectId)))
                || stored.revisionId() != Long.parseLong(task.currentPlanRevision().id())
                || proposal.expectedRevisionNo() != task.currentPlanRevision().revisionNo()
                || proposal.expectedLockVersion() != task.lockVersion()
                || source.lockVersion() != stored.validationLockVersion()
                || task.validation() == null || !proposal.sourceValidationId().equals(task.validation().id())) throw stale();
        requireFixable(task);
        var revised = plans.addRevision(caller, projectId, taskId, proposal.proposedPlan(),
                proposal.expectedRevisionNo(), proposal.expectedLockVersion());
        var validation = accepts.accept(caller, projectId, taskId, Long.parseLong(revised.currentPlanRevision().id()),
                revised.lockVersion()).validation();
        proposals.markApplied(proposalId, Long.parseLong(validation.id()));
        return new TaskPlanProposalApplyResponse(query.getTaskDetail(caller, projectId, taskId), validation);
    }

    private TaskDetailResponse ownedTask(CallerContext caller, long projectId, long taskId) {
        var actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        var task = query.getTaskDetail(caller, projectId, taskId);
        actor.requireMemberId(task.assigneeMemberId() == null ? null : Long.parseLong(task.assigneeMemberId()),
                "담당자만 계획 수정안을 요청하고 승인할 수 있어요.");
        if (task.status().isClosed()) throw new ApiException(HttpStatus.CONFLICT, "invalid_transition", "완료·취소된 작업은 수정할 수 없어요.");
        return task;
    }

    private static ValidationResponse requireFixable(TaskDetailResponse task) {
        var v = task.validation();
        if (v == null || !Set.of("NEEDS_INPUT", "NEEDS_REVIEW").contains(v.status()) || "DELTA".equals(v.trigger())
                || !v.planRevisionId().equals(task.currentPlanRevision().id()) || v.findings().stream().noneMatch(TaskPlanProposalService::fixable)) {
            throw new ApiException(HttpStatus.CONFLICT, "proposal_not_needed", "계획 수정이 필요한 현재 검증 결과가 없어요. 실행 오류는 검증을 다시 시도해 주세요.");
        }
        return v;
    }

    private static boolean fixable(FindingResponse f) {
        return "FIX_PLAN".equals(f.resolution()) && !Set.of("ERROR", "EXECUTION_ERROR", "ANALYSIS_ERROR", "EVIDENCE_COLLECTION_FAILED").contains(f.kind());
    }

    static TaskPlanInput merge(TaskPlanInput before, Object value) {
        if (!(value instanceof Map<?, ?> p)) throw invalidOutput();
        return new TaskPlanInput(before.title(), text(p.get("goal")), strings(p.get("included_scope")),
                strings(p.get("excluded_scope")), text(p.get("approach")), strings(p.get("completion_criteria")),
                before.dependencyTaskIds(), before.designRefs(), before.unlinked(), before.noContractChange(), before.contextPacketId());
    }

    private List<Map<String, Object>> verifiedChanges(TaskPlanInput before, TaskPlanInput after, List<FindingResponse> findings, Object value) {
        var old = asMap(before); var updated = asMap(after);
        Set<String> changed = new HashSet<>();
        for (String field : FIELDS) if (!Objects.equals(old.get(field), updated.get(field))) changed.add(field);
        if (changed.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "proposal_not_needed", "검토할 계획 변경 내용이 없어요.");
        var ids = findings.stream().map(FindingResponse::id).collect(java.util.stream.Collectors.toSet());
        var verified = new ArrayList<Map<String, Object>>();
        for (var change : objects(value)) {
            if (!(change.get("field") instanceof String field) || !changed.remove(field)
                    || !(change.get("reason") instanceof String reason) || reason.isBlank()
                    || !(change.get("finding_ids") instanceof List<?> cited) || cited.isEmpty()
                    || !cited.stream().allMatch(ids::contains)) throw invalidOutput();
            verified.add(Map.of("field", field, "reason", reason, "finding_ids", List.copyOf(cited)));
        }
        if (!changed.isEmpty()) throw invalidOutput();
        return List.copyOf(verified);
    }

    private static String text(Object v) { if (!(v instanceof String s) || s.isBlank()) throw invalidOutput(); return s; }
    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String))) throw invalidOutput();
        return list.stream().map(String.class::cast).toList();
    }
    @SuppressWarnings("unchecked") private Map<String, Object> asMap(Object value) { return json.convertValue(value, Map.class); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof Map<?, ?>))) throw invalidOutput();
        return (List<Map<String, Object>>) list;
    }
    private static ApiException stale() { return new ApiException(HttpStatus.CONFLICT, "proposal_stale", "계획·검증 결과 또는 기준 설계가 바뀌었어요. 최신 내용으로 수정안을 다시 받아 주세요."); }
    private static ApiException invalidOutput() { return new ApiException(HttpStatus.BAD_GATEWAY, "ai_invalid_output", "AI 수정안의 변경 이유나 문서 근거를 확인하지 못했어요. 다시 시도해 주세요."); }
}
