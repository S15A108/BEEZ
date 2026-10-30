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
 *   <li>의미 분석 필요 — unlinked 가 있거나, CHANGES·IMPLEMENTS 참조가 있거나(승인 설계와 대조), 관련 열린 작업이 있음
 *       → 검사 목록(checks)을 만들어 RUNNING</li>
 *   <li>그 밖에는(READS 만 참조하고 unlinked·관련 작업이 없음) PASS</li>
 * </ul>
 * "구조화된 계약의 명확한 불일치"(CHANGES 의 proposed_content 와 승인 내용 비교)는 kind 별 content 스키마가 Q01 에서 정해진
 * 뒤에 넣는다 — 지금은 의미 분석(DESIGN_ALIGNMENT)에 맡긴다.
 */
@Component
public class ValidationRules {

    /** 규칙 묶음 버전 (validation.rule_version). 규칙이 바뀌면 올린다 */
    public static final String RULE_VERSION = "rules-v1";

    private final ProjectDesignLookup designLookup;

    public ValidationRules(ProjectDesignLookup designLookup) {
        this.designLookup = designLookup;
    }

    /**
     * @param relatedOpen 같은 항목을 보는 열린 다른 작업 (행: task_id, plan_revision_id, status, design_item_id, relation)
     */
    public RuleOutcome evaluate(Long projectId, TaskPlanRevision plan, List<TaskPlanDesignRef> refs,
                                List<Map<String, Object>> relatedOpen) {
        List<FindingRow> findings = new ArrayList<>();
        boolean needsInput = false;
        boolean needsReview = false;
        boolean hasChanges = false;

        // 1) 참조 항목·버전이 현재 기준에 있는가
        for (TaskPlanDesignRef ref : refs) {
            if (ref.getRelation() == PlanRelation.CHANGES) {
                hasChanges = true;
            }
            Optional<Long> current = designLookup.currentVersionIdOf(projectId, ref.getDesignItemId());
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
                    "계약 변경 없음(no_contract_change)으로 선언했는데 CHANGES 참조가 있습니다", "FIX_PLAN",
                    "선언을 false 로 바꾸거나 CHANGES 참조를 READS·IMPLEMENTS 로 바꾸세요"));
            needsInput = true;
        }
        if (!plan.isNoContractChange() && !hasChanges && unlinkedEmpty) {
            findings.add(FindingRow.rule("MISSING_INPUT", "BLOCKING", null, null, null,
                    "계약을 바꾼다고 했는데 바꿀 항목(CHANGES)도 미연결 내용(unlinked)도 없습니다", "FIX_PLAN",
                    "바꿀 항목을 CHANGES 로 참조하거나, 아직 설계에 없는 내용이면 unlinked 에 적으세요"));
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
        List<Map<String, Object>> checks = checks(plan, refs, relatedOpen, unlinkedEmpty);
        if (checks.isEmpty()) {
            return new RuleOutcome("PASS", findings, List.of());
        }
        return new RuleOutcome(null, findings, checks);
    }

    /**
     * 검사 목록 (명세 ValidationCheck). 요청 안에서 키가 유일하다.
     * 계획 완결성 1개(unlinked 가 있을 때) + CHANGES·IMPLEMENTS 항목마다 설계 정합성 1개 + 관련 열린 작업마다 충돌 1개.
     * 설계 정합성은 관련 작업 유무와 상관없이 넣는다 — 구조화 계약 비교(Q01)가 아직 없어, 빼면 내용이 승인 설계와 달라도
     * 버전만 맞으면 PASS 가 된다. 목록이 비면(READS 만, unlinked·관련 작업 없음) 의미 분석 없이 PASS.
     */
    private List<Map<String, Object>> checks(TaskPlanRevision plan, List<TaskPlanDesignRef> refs,
                                             List<Map<String, Object>> relatedOpen, boolean unlinkedEmpty) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!unlinkedEmpty) {
            checks.add(check("plan-completeness", "PLAN_COMPLETENESS", List.of(), List.of()));
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
