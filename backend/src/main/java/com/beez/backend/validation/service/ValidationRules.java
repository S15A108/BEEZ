package com.beez.backend.validation.service;

import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.validation.repository.FindingRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 검증 규칙 (공통 6절 "검증·시작" 판정표). 판정은 여기서 내리고 AI 는 발견 후보만 더한다.
 *
 * <ul>
 *   <li>NEEDS_INPUT — 모르는 항목·버전(현재 기준에 없음), CHANGES 없음 + no_contract_change=false + unlinked 비어 있음,
 *       no_contract_change=true 인데 CHANGES 참조 있음. 계획 필수 칸은 등록 때 이미 검사한다</li>
 *   <li>NEEDS_REVIEW — 같은 항목을 CHANGES 하는 IN_PROGRESS·WAITING 작업이 있음 (CHANGES 점유 충돌)</li>
 *   <li>의미 분석 필요 — unlinked 가 있거나, CHANGES·IMPLEMENTS 참조가 있거나(승인 설계와 대조), 관련 열린 작업이 있거나,
 *       완료 delta 를 검사하는 DELTA 검증임 → 검사 목록(checks)을 만들어 RUNNING</li>
 *   <li>그 밖에는(READS 만 참조하고 unlinked·관련 작업이 없음) PASS</li>
 * </ul>
 * "구조화된 계약의 명확한 불일치"(CHANGES 의 proposed_content 와 승인 내용 비교)는 kind 별 content 스키마가 Q01 에서 정해진
 * 뒤에 넣는다 — 지금은 의미 분석(DESIGN_ALIGNMENT)에 맡긴다.
 */
@Component
public class ValidationRules {

    /** 규칙 묶음 버전 (validation.rule_version). 규칙이 바뀌면 올린다 */
    public static final String RULE_VERSION = "rules-v2";
    public static final String POLICY_RULE_VERSION = "rules-v3-policy-v1";
    public static final String POLICY_CHECK = "policy-gap-v1";

    /** DELTA 검증의 완료 delta 검사 키. 워커가 같은 요청에 completion_delta 를 싣는다 */
    public static final String COMPLETION_DELTA_CHECK = "completion-delta";

    private final ProjectDesignLookup designLookup;

    public ValidationRules(ProjectDesignLookup designLookup) {
        this.designLookup = designLookup;
    }

    /**
     * @param baselineId  참조 버전을 대조할 기준 설계. 보통 현재 기준이지만, 설계 발행 트랜잭션 안에서는 아직 현재 포인터가 되기 전인
     *                    새 기준을 받는다 (-163 재접수)
     * @param relatedOpen 같은 항목을 보는 열린 다른 작업 (행: task_id, plan_revision_id, status, design_item_id, relation)
     * @param completionDelta 완료 보고의 delta 를 검사하는 DELTA 검증인가. 그러면 규칙만으로 PASS 하지 않고 완료 delta 검사를 넣는다
     *                        (delta 내용과 무관하게 완료되는 일이 없게)
     */
    public RuleOutcome evaluate(Long projectId, Long baselineId, TaskPlanRevision plan, List<TaskPlanDesignRef> refs,
                                List<Map<String, Object>> relatedOpen, boolean completionDelta) {
        return evaluate(projectId, baselineId, plan, refs, relatedOpen, completionDelta, false);
    }

    public RuleOutcome evaluate(Long projectId, Long baselineId, TaskPlanRevision plan, List<TaskPlanDesignRef> refs,
                                List<Map<String, Object>> relatedOpen, boolean completionDelta, boolean policyEnabled) {
        List<FindingRow> findings = new ArrayList<>();
        boolean needsInput = false;
        boolean needsReview = false;
        boolean hasChanges = false;

        // 1) 참조 항목·버전이 현재 기준에 있는가
        for (TaskPlanDesignRef ref : refs) {
            if (ref.getRelation() == PlanRelation.CHANGES) {
                hasChanges = true;
            }
            Optional<Long> current = designLookup.versionIdIn(projectId, baselineId, ref.getDesignItemId());
            if (current.isEmpty()) {
                findings.add(FindingRow.rule("STALE_REF", "BLOCKING", ref.getDesignItemId(), ref.getDesignItemVersionId(), null,
                        "참조한 설계 항목이 현재 기준 설계에 없습니다 (폐기되었거나 모르는 항목)", "FIX_PLAN",
                        "현재 기준의 항목으로 참조를 바꾸거나 참조를 빼고 계획을 다시 올리세요"));
                needsInput = true;
            } else if (!current.get().equals(ref.getDesignItemVersionId())) {
                findings.add(FindingRow.rule("STALE_REF", "BLOCKING", ref.getDesignItemId(), ref.getDesignItemVersionId(), null,
                        "참조한 항목 버전이 현재 기준의 버전과 다릅니다", "FIX_PLAN",
                        "현재 기준 버전으로 계획을 다시 올리세요"));
                needsInput = true;
            }
        }

        // 2) 계약 변경 선언과 참조의 모순
        boolean unlinkedEmpty = plan.getUnlinked() == null || plan.getUnlinked().isEmpty();
        if (plan.isNoContractChange() && hasChanges) {
            findings.add(FindingRow.rule("MISSING_INPUT", "BLOCKING", null, null, null,
                    "계약 변경 없음(no_contract_change)이라고 했는데 계약 변경(CHANGES)으로 고른 설계 항목이 있습니다", "FIX_PLAN",
                    "계약을 바꾸는 작업이면 계약 변경 없음을 끄고, 아니면 그 항목을 구현·참고만(IMPLEMENTS·READS)으로 바꾸세요"));
            needsInput = true;
        }
        if (!plan.isNoContractChange() && !hasChanges && unlinkedEmpty) {
            findings.add(FindingRow.rule("MISSING_INPUT", "BLOCKING", null, null, null,
                    "계약을 바꾼다고 했는데(no_contract_change=false) 계약 변경(CHANGES)으로 고른 설계 항목도, 설계에 없는 내용(unlinked)도 없습니다", "FIX_PLAN",
                    "계약을 바꾸지 않는 작업이면 계약 변경 없음을 켜고, 바꾸는 작업이면 바꿀 항목을 계약 변경(CHANGES)으로 고르거나 설계에 없는 내용(unlinked)에 적으세요"));
            needsInput = true;
        }

        // 3) 같은 항목을 CHANGES 로 점유한 진행 중 작업
        Set<Long> changedHere = new LinkedHashSet<>();
        for (TaskPlanDesignRef ref : refs) {
            if (ref.getRelation() == PlanRelation.CHANGES) {
                changedHere.add(ref.getDesignItemId());
            }
        }
        for (Map<String, Object> row : relatedOpen) {
            long item = ((Number) row.get("design_item_id")).longValue();
            String status = (String) row.get("status");
            boolean occupied = "IN_PROGRESS".equals(status) || "WAITING".equals(status);
            if (changedHere.contains(item) && "CHANGES".equals(row.get("relation")) && occupied) {
                long otherTask = ((Number) row.get("task_id")).longValue();
                findings.add(FindingRow.rule("CONFLICT", "BLOCKING", item, versionOf(refs, item), otherTask,
                        "같은 설계 항목을 다른 진행 중 작업이 이미 바꾸고 있습니다", "HUMAN_REVIEW",
                        "그 작업이 끝난 뒤 다시 검증하거나 팀장과 변경 범위를 나누세요"));
                needsReview = true;
            }
        }

        if (needsInput) {
            return new RuleOutcome("NEEDS_INPUT", findings, List.of());
        }
        if (needsReview) {
            return new RuleOutcome("NEEDS_REVIEW", findings, List.of());
        }

        // 4) 의미 분석이 필요한가: 미연결 내용·바꾸거나 구현하는 설계 항목·같은 항목을 보는 열린 작업 중 하나라도 있을 때
        List<Map<String, Object>> checks = checks(plan, refs, relatedOpen, unlinkedEmpty, completionDelta);
        if (policyEnabled) {
            checks.add(check(POLICY_CHECK, "POLICY_ALIGNMENT", refs.stream()
                    .map(ref -> String.valueOf(ref.getDesignItemVersionId())).distinct().toList(), List.of()));
        }
        if (checks.isEmpty()) {
            return new RuleOutcome("PASS", findings, List.of());
        }
        return new RuleOutcome(null, findings, checks);
    }

    /**
     * 검사 목록 (명세 ValidationCheck). 요청 안에서 키가 유일하다.
     * 계획 완결성 1개(unlinked 가 있을 때) + CHANGES·IMPLEMENTS 항목마다 설계 정합성 1개 + 관련 열린 작업마다 충돌 1개
     * + DELTA 검증이면 완료 delta 검사 1개({@value #COMPLETION_DELTA_CHECK}, 계획 완결성 종류 — 요청의 completion_delta 가 계획·설계와 맞는지).
     * 설계 정합성은 관련 작업 유무와 상관없이 넣는다 — 구조화 계약 비교(Q01)가 아직 없어, 빼면 내용이 승인 설계와 달라도
     * 버전만 맞으면 PASS 가 된다. 목록이 비면(READS 만, unlinked·관련 작업 없음) 의미 분석 없이 PASS.
     */
    private List<Map<String, Object>> checks(TaskPlanRevision plan, List<TaskPlanDesignRef> refs,
                                             List<Map<String, Object>> relatedOpen, boolean unlinkedEmpty, boolean completionDelta) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!unlinkedEmpty) {
            checks.add(check("plan-completeness", "PLAN_COMPLETENESS", List.of(), List.of()));
        }
        if (completionDelta) {
            checks.add(check(COMPLETION_DELTA_CHECK, "PLAN_COMPLETENESS", List.of(), List.of()));
        }
        for (TaskPlanDesignRef ref : refs) {
            if (ref.getRelation() == PlanRelation.CHANGES || ref.getRelation() == PlanRelation.IMPLEMENTS) {
                checks.add(check("design-" + ref.getDesignItemId(), "DESIGN_ALIGNMENT",
                        List.of(String.valueOf(ref.getDesignItemVersionId())), List.of()));
            }
        }
        Map<Long, Long> relatedPlans = new LinkedHashMap<>();
        for (Map<String, Object> row : relatedOpen) {
            relatedPlans.put(((Number) row.get("task_id")).longValue(), ((Number) row.get("plan_revision_id")).longValue());
        }
        for (Map.Entry<Long, Long> e : relatedPlans.entrySet()) {
            checks.add(check("related-task-" + e.getKey(), "RELATED_PLAN_CONFLICT", List.of(),
                    List.of(String.valueOf(e.getValue()))));
        }
        return checks;
    }

    private static Map<String, Object> check(String key, String kind, List<String> versionIds, List<String> revisionIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("kind", kind);
        m.put("design_item_version_ids", versionIds);
        m.put("related_plan_revision_ids", revisionIds);
        return m;
    }

    private static Long versionOf(List<TaskPlanDesignRef> refs, long itemId) {
        for (TaskPlanDesignRef ref : refs) {
            if (ref.getDesignItemId() == itemId) {
                return ref.getDesignItemVersionId();
            }
        }
        return null;
    }
}
