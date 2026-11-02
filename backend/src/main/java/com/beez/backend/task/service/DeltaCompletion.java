package com.beez.backend.task.service;

import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.task.repository.TaskRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S4 — DELTA 검증이 PASS 하면 서버가 작업을 COMPLETED 로 바꾼다 (04-task-lifecycle.md §2.2·§2.3, REST TSK-A07).
 * 조건: 작업이 아직 WAITING(REVALIDATION) 이고, 현재 계획에 대한 delta 완료 보고가 있고, 이 검증이 현재 계획의
 * <b>가장 최근 DELTA 검증</b>일 때만. (완료 보고의 delta_validation_id 와 같은지가 아니라 "가장 최근" 으로 보는 이유:
 * 설계 발행으로 DELTA 검증이 STALE 이 되면 서버가 새 DELTA 검증을 접수하는데(-163), 완료 보고는 UPDATE 금지라 그 번호를
 * 새로 적을 수 없다. 새 DELTA 가 PASS 하면 그 작업은 완료돼야 한다.)
 *
 * <p>부르는 곳: 검증 결과 반영(워커, PASS 로 끝날 때), 완료 보고(규칙만으로 바로 PASS 가 난 경우), 설계 발행 뒤 재접수.
 * 모두 프로젝트 행을 잠근 트랜잭션 안이다. 활동 기록(TASK_COMPLETED, 계약 10)은 -64 에서 붙인다.
 */
@Component
public class DeltaCompletion {

    private static final Logger log = LoggerFactory.getLogger(DeltaCompletion.class);

    private final TaskRepository tasks;
    private final JdbcClient jdbc;

    public DeltaCompletion(TaskRepository tasks, JdbcClient jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    /**
     * @return 작업을 COMPLETED 로 바꿨으면 true. 조건이 안 맞으면 아무것도 바꾸지 않고 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeIfDeltaPassed(long taskId, long validationId, Instant now) {
        Optional<Task> found = tasks.findById(taskId);
        if (found.isEmpty()) {
            return false;
        }
        Task task = found.get();
        if (task.getStatus() != TaskStatus.WAITING || task.getWaitingReason() != WaitingReason.REVALIDATION) {
            return false;
        }
        if (!isLatestDeltaOfCurrentPlan(task, validationId) || !hasDeltaReport(task)) {
            return false;
        }
        task.complete(now);
        tasks.saveAndFlush(task);
        log.info("task {} completed by DELTA validation {} (S4)", taskId, validationId);
        return true;
    }

    /** 현재 계획의 가장 최근 DELTA 검증(상태 무관)이 바로 이 검증이고, 그 상태가 PASS 인가 */
    private boolean isLatestDeltaOfCurrentPlan(Task task, long validationId) {
        Optional<Map<String, Object>> latest = jdbc.sql("""
                        select id, status from validation
                         where task_id = :t and plan_revision_id = :r and trigger = 'DELTA'
                         order by id desc limit 1
                        """)
                .param("t", task.getId()).param("r", task.getCurrentPlanRevisionId()).query().listOfRows().stream().findFirst();
        if (latest.isEmpty()) {
            return false;
        }
        long latestId = ((Number) latest.get().get("id")).longValue();
        return latestId == validationId && "PASS".equals(latest.get().get("status"));
    }

    /** 현재 계획에 대한 delta 있는 완료 보고가 있는가 (없으면 WAITING(REVALIDATION) 은 S1·S2·S5 로 들어온 것이라 S4 대상이 아니다) */
    private boolean hasDeltaReport(Task task) {
        Integer count = jdbc.sql("""
                        select count(*) from task_completion_report
                         where task_id = :t and plan_revision_id = :r and has_delta = true
                        """)
                .param("t", task.getId()).param("r", task.getCurrentPlanRevisionId()).query(Integer.class).single();
        return count > 0;
    }
}
