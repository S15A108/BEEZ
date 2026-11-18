package com.beez.backend.task.service;

import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S4 — DELTA 검증이 PASS 하면 서버가 작업을 COMPLETED 로 바꾼다 (04-task-lifecycle.md §2.2·§2.3, REST TSK-A07).
 * 조건: 작업이 아직 WAITING(REVALIDATION) 이고, 현재 계획에 delta 완료 보고가 있고, 이 검증이 현재 계획의
 * <b>가장 최근 DELTA 검증</b>이며 PASS 일 때만. (완료 보고의 delta_validation_id 와 같은지가 아니라 "가장 최근" 으로 보는 이유:
 * 설계 발행으로 DELTA 검증이 STALE 이 되면 서버가 새 DELTA 검증을 접수하는데(-163), 완료 보고는 UPDATE 금지라 그 번호를
 * 새로 적을 수 없다. 새 DELTA 가 PASS 하면 그 작업은 완료돼야 한다.) RESUME 한 작업의 옛 DELTA 는 RESUME 때 STALE 로 바뀌어
 * ({@link ValidationStatusLookup#invalidateDeltaOnResume}) 여기 오지 못한다.
 *
 * <p>가짜 분석기(beez.validation.fake-analyzer)가 낸 PASS 는 시작 관문 G1 과 같은 규칙으로, 가짜 분석기를 끈 서버에서는 완료로 쓰지 않는다.
 *
 * <p>부르는 곳: 검증 결과 반영(워커, PASS 로 끝날 때)과 설계 발행 뒤 재접수. 모두 프로젝트 행을 잠근 트랜잭션 안이다.
 * 완료로 바꾸면 같은 트랜잭션에서 시스템 행위로 {@code TASK_COMPLETED} 활동을 남긴다 (계약 10 6.2, -287).
 */
@Component
public class DeltaCompletion {

    private static final Logger log = LoggerFactory.getLogger(DeltaCompletion.class);

    private final TaskRepository tasks;
    private final TaskCompletionReportRepository reports;
    private final ValidationStatusLookup validations;
    private final boolean fakeAnalyzerEnabled;
    private final TaskActivities activities;

    public DeltaCompletion(TaskRepository tasks, TaskCompletionReportRepository reports, ValidationStatusLookup validations,
                           @Value("${beez.validation.fake-analyzer:false}") boolean fakeAnalyzerEnabled,
                           TaskActivities activities) {
        this.tasks = tasks;
        this.reports = reports;
        this.validations = validations;
        this.fakeAnalyzerEnabled = fakeAnalyzerEnabled;
        this.activities = activities;
    }

    /**
     * @param analyzerVersion 이 PASS 를 낸 분석기(실행 기록의 analyzer_version). 규칙만으로 끝났으면 null
     * @return 작업을 COMPLETED 로 바꿨으면 true. 조건이 안 맞으면 아무것도 바꾸지 않고 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeIfDeltaPassed(long taskId, long validationId, String analyzerVersion, Instant now) {
        if (!fakeAnalyzerEnabled && FakeValidationAnalyzer.MODEL.equals(analyzerVersion)) {
            log.info("task {} not completed: DELTA validation {} passed by the fake analyzer while it is off", taskId, validationId);
            return false;
        }
        Optional<Task> found = tasks.findById(taskId);
        if (found.isEmpty()) {
            return false;
        }
        Task task = found.get();
        if (task.getStatus() != TaskStatus.WAITING || task.getWaitingReason() != WaitingReason.REVALIDATION) {
            return false;
        }
        Long latest = validations.latestDeltaValidationId(task.getId(), task.getCurrentPlanRevisionId());
        if (latest == null || latest != validationId || !validations.isPass(validationId)) {
            return false;
        }
        if (!reports.existsByTaskIdAndPlanRevisionIdAndHasDeltaTrue(task.getId(), task.getCurrentPlanRevisionId())) {
            return false;
        }
        task.complete(now);
        tasks.saveAndFlush(task);
        activities.completedBySystem(task);   // 키는 flush 뒤 잠금 버전 — 완료 보고(S3) 때보다 커서 겹치지 않는다
        log.info("task {} completed by DELTA validation {} (S4)", taskId, validationId);
        return true;
    }
}
