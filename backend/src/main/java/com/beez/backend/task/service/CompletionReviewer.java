package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.task.dto.CompletionReviewResponse;
import com.beez.backend.task.dto.CompletionReviewResponse.ScopeDeviation;
import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskCompletionReport;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 작업 완료 검증 (S15P31A108-269, VAL-07 작은 범위). 완료 보고를 계획과 규칙으로 대조한다. AI 호출 없음.
 *
 * <p>정한 것 (11/5): 결과는 <b>표시만</b> 한다 — 범위 이탈 후보가 있어도 완료 전이는 그대로다 (AC-18 보류안은 하지 않음).
 * 대조 결과는 저장하지 않고 읽을 때 계산한다. 보고·계획 리비전·계획 참조가 모두 불변이라 같은 결과가 나온다.
 * 인수인계 기록 유무만 지금 상태를 본다 (완료 뒤에 남겨도 경고가 사라지게).
 */
@Component
public class CompletionReviewer {

    public static final String REVIEW_VERSION = "completion-review-v1";
    static final String NOTICE = "계획과 보고 내용을 규칙으로 대조한 결과입니다. 코드 품질이나 동작을 보증하지 않습니다.";

    private final TaskCompletionReportRepository reports;
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;
    private final ValidationStatusLookup validations;
    private final JdbcClient jdbc;

    public CompletionReviewer(TaskCompletionReportRepository reports, TaskPlanRevisionRepository revisions,
                              TaskPlanDesignRefRepository designRefs, ValidationStatusLookup validations, JdbcClient jdbc) {
        this.reports = reports;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.validations = validations;
        this.jdbc = jdbc;
    }

    /**
     * 요청의 changed_design_item_ids 를 숫자로 바꾸고 이 프로젝트의 설계 항목인지 확인한다.
     * null 이면 null (보고 안 함). 중복은 하나로, 순서는 오름차순으로 맞춘다 — 같은 보고의 재전송을 순서와 관계없이 같게 본다.
     * 없는 항목·다른 프로젝트 항목은 400 validation_failed (어느 쪽인지 구분하지 않는다)
     */
    public List<Long> normalizeChangedItems(Long projectId, List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>(new TreeSet<>(Ids.parseAll(raw, "changed_design_item_ids")));
        if (ids.isEmpty()) {
            return ids;
        }
        List<Long> found = jdbc.sql("select id from design_item where project_id = :projectId and id in (:ids)")
                .param("projectId", projectId)
                .param("ids", ids)
                .query(Long.class)
                .list();
        if (found.size() != ids.size()) {
            Set<Long> missing = new TreeSet<>(ids);
            found.forEach(missing::remove);
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "이 프로젝트에 없는 설계 항목입니다: " + Ids.strs(new ArrayList<>(missing)));
        }
        return ids;
    }

    /**
     * 작업에 지금 보여 줄 완료 검증 결과. 다음 경우만 있다.
     * <ul>
     *   <li>COMPLETED — 현재 계획의 가장 최근 완료 보고</li>
     *   <li>WAITING(REVALIDATION) 이고 delta 완료 보고의 DELTA 검증을 기다리는 중 — 그 보고</li>
     * </ul>
     * 그 밖의 상태(RESUME 로 보고를 거둔 뒤 등)는 옛 보고를 보여 주지 않는다
     */
    public Optional<CompletionReviewResponse> currentReview(Task task) {
        Long planId = task.getCurrentPlanRevisionId();
        if (planId == null) {
            return Optional.empty();
        }
        Optional<TaskCompletionReport> report;
        if (task.getStatus() == TaskStatus.COMPLETED) {
            report = reports.findFirstByTaskIdAndPlanRevisionIdOrderByIdDesc(task.getId(), planId);
        } else if (task.getStatus() == TaskStatus.WAITING && task.getWaitingReason() == WaitingReason.REVALIDATION
                && validations.pendingDeltaValidationId(task.getId(), planId) != null) {
            report = reports.findFirstByTaskIdAndPlanRevisionIdAndHasDeltaTrueOrderByIdDesc(task.getId(), planId);
        } else {
            report = Optional.empty();
        }
        return report.map(this::review);
    }

    CompletionReviewResponse review(TaskCompletionReport report) {
        TaskPlanRevision plan = revisions.findById(report.getPlanRevisionId())
                .orElseThrow(() -> new IllegalStateException("plan_revision_missing id=" + report.getPlanRevisionId()));
        Map<Long, PlanRelation> planned = new HashMap<>();
        for (TaskPlanDesignRef ref : designRefs.findByPlanRevisionId(plan.getId())) {
            planned.put(ref.getDesignItemId(), ref.getRelation());
        }

        List<Long> changed = report.getChangedDesignItemIds();
        List<ScopeDeviation> deviations = new ArrayList<>();
        List<String> unreported = new ArrayList<>();
        if (changed != null) {
            for (Long id : changed) {
                String reason = deviationReason(plan.isNoContractChange(), planned.get(id));
                if (reason != null) {
                    deviations.add(new ScopeDeviation(Ids.str(id), reason));
                }
            }
            Set<Long> changedSet = new HashSet<>(changed);
            new TreeSet<>(planned.keySet()).forEach(id -> {
                if (planned.get(id) == PlanRelation.CHANGES && !changedSet.contains(id)) {
                    unreported.add(Ids.str(id));
                }
            });
        }

        List<String> unmet = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        for (Map<String, Object> check : report.getCompletionChecks()) {
            String criterion = String.valueOf(check.get("criterion"));
            checked.add(criterion.strip());
            if (Boolean.FALSE.equals(check.get("met"))) {
                unmet.add(criterion);
            }
        }
        List<String> unchecked = new ArrayList<>();
        for (String criterion : plan.getCompletionCriteria()) {
            if (!checked.contains(criterion.strip())) {
                unchecked.add(criterion);
            }
        }

        return new CompletionReviewResponse(REVIEW_VERSION, Ids.str(report.getId()), changed != null, deviations,
                unreported, unmet, unchecked, handoverRecorded(report.getTaskId()), deviations.size(), NOTICE);
    }

    /**
     * 보고된 변경 한 건이 범위 이탈 후보인가. null = 계획대로.
     * 계획이 "계약 변경 없음"을 선언했으면 어떤 변경이든 그 사유가 먼저다
     */
    static String deviationReason(boolean noContractChange, PlanRelation plannedRelation) {
        if (noContractChange) {
            return "NO_CONTRACT_CHANGE_DECLARED";
        }
        if (plannedRelation == null) {
            return "NOT_IN_PLAN";
        }
        if (plannedRelation != PlanRelation.CHANGES) {
            return "NOT_PLANNED_TO_CHANGE";
        }
        return null;
    }

    /** 공유 인수인계 기록이 있는가 (write_context kind=HANDOVER, visibility=SHARED). 개인 기록은 다른 사람이 못 보니 세지 않는다 */
    private boolean handoverRecorded(Long taskId) {
        return jdbc.sql("""
                        select exists(select 1 from context_record
                                       where task_id = :taskId and kind = 'HANDOVER' and visibility = 'SHARED')
                        """)
                .param("taskId", taskId)
                .query(Boolean.class)
                .single();
    }
}
