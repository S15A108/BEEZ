package com.beez.backend.validation.service;

import com.beez.backend.changeproposal.dto.PublicationDtos.ChangedItem;
import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactRequest;
import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactResult;
import com.beez.backend.changeproposal.dto.PublicationDtos.StaleValidation;
import com.beez.backend.changeproposal.dto.PublicationDtos.WaitingTask;
import com.beez.backend.changeproposal.port.ValidationImpactPort;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.service.DeltaCompletion;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.task.service.ValidationStatusLookup;
import com.beez.backend.validation.dto.AcceptResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설계 발행 수신 (S15P31A108-163) — 변경 제안 승인·발행 트랜잭션이 부르는 {@link ValidationImpactPort} 의 실제 구현.
 * 생애주기 S1 (04-task-lifecycle.md §2.2), REST CHG 발행 처리 규칙:
 * <ul>
 *   <li><b>검증 STALE</b> — 바뀐 항목(수정·폐기)의 <b>변경 전 버전 또는 그보다 옛 버전</b>을 참조(validation_design_ref)한 검증.
 *       끝난 것(PASS·NEEDS_*)과 도는 것(NOT_RUN·RUNNING)을 모두 {@code DESIGN_PUBLISHED} 로 무효화하고 대기 job 은 취소한다.
 *       작업 상태·현재 계획으로 거르지 않는다 (완료된 작업·옛 리비전의 검증도 STALE)</li>
 *   <li><b>작업 WAITING(REVALIDATION)</b> — 그 버전을 <b>현재 계획</b>이 참조하는 <b>IN_PROGRESS</b> 작업만. READY·WAITING 은 상태를
 *       두고 검증만 STALE (START·RESUME 때 G1·G2 가 거른다). 점유는 유지</li>
 *   <li><b>DELTA 재접수</b> — 무효화된 검증이 delta 완료 보고의 DELTA 였고 작업이 아직 WAITING(REVALIDATION) 이면 새 기준으로
 *       새 DELTA 검증을 바로 접수한다(§2.3 "STALE 은 재시도가 아니라 새 검증"). 그 작업은 S4 로만 완료될 수 있어 서버가 대신 요청한다</li>
 * </ul>
 * 발행 트랜잭션 안에서 돌고(MANDATORY), 예외는 발행 전체를 롤백한다. 멱등: 같은 발행이 두 번 적용돼도 STALE 은 한 번만 바뀌고
 * 이미 WAITING 인 작업은 다시 바꾸지 않으며, 재접수는 "가장 최근 DELTA 가 방금 STALE 이 된 것" 에만 한다.
 * 호출 시점은 현재 기준 포인터가 새 기준으로 바뀌기 <b>전</b>이므로, 재접수 규칙은 요청에 든 새 기준 번호로 대조한다.
 *
 * <p>담당자 알림(계약 10 "설계 발행 수신·무효화": 작업마다 REVALIDATION_REQUIRED, 키 task:{작업}:revalidation:{제안}:to:{계정})은
 * 알림 모듈(-181)이 붙으면 이 트랜잭션 안에서 요청한다 — 대상은 돌려주는 ImpactResult 의 두 목록이다.
 */
@Component
public class PublicationImpactAdapter implements ValidationImpactPort {

    private static final Logger log = LoggerFactory.getLogger(PublicationImpactAdapter.class);

    public static final String STALE_REASON = "DESIGN_PUBLISHED";

    private final JdbcClient jdbc;
    private final TaskRepository tasks;
    private final ProjectDesignLookup designLookup;
    private final ValidationStatusLookup invalidation;
    private final ValidationAcceptService accepts;
    private final DeltaCompletion deltaCompletion;

    public PublicationImpactAdapter(JdbcClient jdbc, TaskRepository tasks, ProjectDesignLookup designLookup,
                                    ValidationStatusLookup invalidation, ValidationAcceptService accepts,
                                    DeltaCompletion deltaCompletion) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.designLookup = designLookup;
        this.invalidation = invalidation;
        this.accepts = accepts;
        this.deltaCompletion = deltaCompletion;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ImpactResult applyPublication(CallerContext caller, long projectId, ImpactRequest request) {
        if (request.changedItems().isEmpty()) {
            return ImpactResult.empty();          // 문서만 바뀐 발행. 참조 버전이 그대로라 영향 없음
        }
        // 작업 상태 보고·계획 변경·검증 반영과 같은 순서(project → task → validation)로 프로젝트 행을 먼저 잠근다.
        // 이 뒤로 트랜잭션이 끝날 때까지 같은 프로젝트의 START·RESUME·계획 변경·검증 결과 반영이 기다린다
        designLookup.lockProject(projectId);

        List<Map<String, Object>> stale = staleCandidates(projectId, request.changedItems());
        List<Long> staleIds = new ArrayList<>();
        for (Map<String, Object> row : stale) {
            staleIds.add(((Number) row.get("validation_id")).longValue());
        }
        int changed = invalidation.invalidateByIds(staleIds, STALE_REASON);

        List<Task> affected = affectedInProgress(projectId, request.changedItems());
        Instant now = Instant.now();
        for (Task task : affected) {
            task.holdForRevalidation();
        }
        if (!affected.isEmpty()) {
            tasks.saveAllAndFlush(affected);
        }

        // DELTA 재접수: 방금 STALE 이 된 검증 중 DELTA 이고, 그 작업이 이 발행 전부터 delta 완료 보고로 기다리던 중이면 새 기준으로
        // 새 DELTA 를 받는다. 방금 IN_PROGRESS 에서 WAITING 으로 내린 작업은 제외 — 옛 완료 보고가 남아 있어도 다시 일하던 작업이라
        // 서버가 완료를 판단할 대상이 아니다 (새 PASS 를 받아 RESUME 하는 길로 간다)
        Set<Long> justHeld = new LinkedHashSet<>();
        for (Task task : affected) {
            justHeld.add(task.getId());
        }
        for (Map<String, Object> row : stale) {
            if (!"DELTA".equals(row.get("trigger"))) {
                continue;
            }
            long taskId = ((Number) row.get("task_id")).longValue();
            if (justHeld.contains(taskId)) {
                continue;
            }
            Optional<Task> task = tasks.findById(taskId);
            if (task.isEmpty() || !awaitingDelta(task.get(), ((Number) row.get("validation_id")).longValue())) {
                continue;
            }
            AcceptResult again = accepts.acceptBySystem(projectId, task.get(), "DELTA", request.baselineId());
            if (again.finished() && "PASS".equals(again.validation().status())) {
                deltaCompletion.completeIfDeltaPassed(taskId, Long.parseLong(again.validation().id()), now);
            }
            log.info("task {} DELTA validation re-accepted after publication: {} ({})", taskId, again.validation().id(),
                    again.validation().status());
        }
        log.info("design publication {} → project {}: {} validations stale, {} tasks waiting for revalidation",
                request.baselineId(), projectId, changed, affected.size());
        return result(stale, affected);
    }

    /** 쓰기·잠금 없이 같은 계산. 발행 미리보기(REPEATABLE_READ 스냅샷)에서 부른다 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ImpactResult previewPublication(long projectId, ImpactRequest request) {
        if (request.changedItems().isEmpty()) {
            return ImpactResult.empty();
        }
        return result(staleCandidates(projectId, request.changedItems()), affectedInProgress(projectId, request.changedItems()));
    }

    /**
     * STALE 대상: 바뀐 항목의 변경 전 버전(번호) 이하를 참조한, 아직 쓸 수 있는(끝났거나 도는) 검증.
     * 행: validation_id, task_id, task_title, trigger. 검증 번호 오름차순, 중복 없음
     */
    private List<Map<String, Object>> staleCandidates(long projectId, List<ChangedItem> changedItems) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (ChangedItem item : changedItems) {
            List<Map<String, Object>> rows = jdbc.sql("""
                            select v.id as validation_id, v.task_id, t.title as task_title, v.trigger
                              from validation v
                              join task t on t.id = v.task_id
                             where t.project_id = :p
                               and v.status in ('NOT_RUN', 'RUNNING', 'PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW')
                               and exists (select 1
                                             from validation_design_ref r
                                             join design_item_version rv on rv.id = r.design_item_version_id
                                             join design_item_version bv on bv.id = :before
                                            where r.validation_id = v.id
                                              and r.design_item_id = :item
                                              and rv.version_no <= bv.version_no)
                             order by v.id
                            """)
                    .param("p", projectId).param("item", item.designItemId()).param("before", item.previousVersionId())
                    .query().listOfRows();
            for (Map<String, Object> row : rows) {
                byId.put(((Number) row.get("validation_id")).longValue(), row);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** WAITING 대상: 바뀐 항목의 변경 전 버전 이하를 현재 계획이 참조하는 IN_PROGRESS 작업 (DesignChangeQueries.affected 와 같은 기준) */
    private List<Task> affectedInProgress(long projectId, List<ChangedItem> changedItems) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ChangedItem item : changedItems) {
            ids.addAll(jdbc.sql("""
                            select distinct t.id
                              from task t
                              join task_plan_design_ref r on r.plan_revision_id = t.current_plan_revision_id
                              join design_item_version rv on rv.id = r.design_item_version_id
                              join design_item_version bv on bv.id = :before
                             where t.project_id = :p
                               and t.status = 'IN_PROGRESS'
                               and r.design_item_id = :item
                               and rv.version_no <= bv.version_no
                             order by t.id
                            """)
                    .param("p", projectId).param("item", item.designItemId()).param("before", item.previousVersionId())
                    .query(Long.class).list());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return tasks.findByProjectIdAndIdIn(projectId, new ArrayList<>(ids));
    }

    /** delta 완료 보고로 기다리는 작업이고, 방금 무효화된 검증이 그 작업 현재 계획의 가장 최근 DELTA 인가 */
    private boolean awaitingDelta(Task task, long staleValidationId) {
        if (task.getStatus() != TaskStatus.WAITING || task.getWaitingReason() != WaitingReason.REVALIDATION) {
            return false;
        }
        Optional<Long> latest = jdbc.sql("""
                        select id from validation
                         where task_id = :t and plan_revision_id = :r and trigger = 'DELTA'
                         order by id desc limit 1
                        """)
                .param("t", task.getId()).param("r", task.getCurrentPlanRevisionId()).query(Long.class).optional();
        if (latest.isEmpty() || latest.get() != staleValidationId) {
            return false;
        }
        Integer reports = jdbc.sql("""
                        select count(*) from task_completion_report
                         where task_id = :t and plan_revision_id = :r and has_delta = true
                        """)
                .param("t", task.getId()).param("r", task.getCurrentPlanRevisionId()).query(Integer.class).single();
        return reports > 0;
    }

    private static ImpactResult result(List<Map<String, Object>> stale, List<Task> affected) {
        List<StaleValidation> validations = new ArrayList<>();
        for (Map<String, Object> row : stale) {
            validations.add(new StaleValidation(Ids.str(((Number) row.get("validation_id")).longValue()),
                    Ids.str(((Number) row.get("task_id")).longValue()), (String) row.get("task_title")));
        }
        List<WaitingTask> waiting = new ArrayList<>();
        for (Task task : affected) {
            waiting.add(new WaitingTask(Ids.str(task.getId()), task.getTitle(), Ids.str(task.getAssigneeMemberId())));
        }
        return new ImpactResult(validations, waiting);
    }
}
