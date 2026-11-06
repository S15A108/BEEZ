package com.beez.backend.validation.service;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityResult;
import com.beez.backend.activity.ActivityTargetType;
import com.beez.backend.activity.SystemActor;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증 완료 활동 {@code VALIDATION_FINISHED} (S15P31A108-287, 계약 docs/design/10-notification-activity-contract.md 6.2 "검증 완료 정하기").
 *
 * <ul>
 *   <li><b>언제</b>: 검증이 끝 상태(PASS·NEEDS_INPUT·NEEDS_REVIEW·ERROR)가 된 순간, 그 결과를 저장한 같은 트랜잭션에서.
 *       STALE(무효화)·늦게 와서 버린 결과·이어 받은 복사본({@code carryForward})은 남기지 않는다 — 새로 끝난 것이 아니다</li>
 *   <li><b>행위자</b>: 요청 처리 안에서 규칙만으로 끝나면 요청자({@link #finishedBy}), 워커가 끝내면 시스템({@link #finishedBySystem})</li>
 *   <li><b>결과</b>: PASS 만 SUCCESS, 나머지는 FAILURE</li>
 *   <li><b>키</b>: {@code validation:{id}:finished:{시도 번호}} — ERROR 뒤 재시도(VAL-A04)로 같은 검증이 다시 끝나도 겹치지 않는다.
 *       접수 때 규칙으로 끝난 검증은 시도 번호가 0 이다</li>
 * </ul>
 */
@Component
public class ValidationActivities {

    static final String VALIDATION_FINISHED = "VALIDATION_FINISHED";
    private static final Set<String> FINISHED = Set.of("PASS", "NEEDS_INPUT", "NEEDS_REVIEW", "ERROR");

    private final ActivityRecorder activities;

    public ValidationActivities(ActivityRecorder activities) {
        this.activities = activities;
    }

    /** 요청 처리 안에서 규칙만으로 끝난 검증 (VAL-A01 접수·완료 보고의 DELTA 접수·발행 뒤 재접수) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishedBy(CallerContext caller, ValidationRow v, String status) {
        activities.record(caller, event(v, status));
    }

    /** 워커가 끝낸 검증 (결과 반영·모델 호출 전 규칙 판정·실패·리스 만료) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishedBySystem(ValidationRow v, String status) {
        activities.recordBy(SystemActor.SYSTEM, event(v, status));
    }

    /**
     * @param v      끝낸 검증. 시도 번호는 끝낸 시도({@code current_attempt}) — 워커는 {@code current_attempt} 가 리스의 시도와 같을 때만 반영한다
     * @param status 끝낸 상태. {@code v} 는 끝내기 전에 읽은 행이라 상태를 따로 받는다
     */
    private static ActivityEvent event(ValidationRow v, String status) {
        if (!FINISHED.contains(status)) {
            throw new IllegalArgumentException("VALIDATION_FINISHED is only for finished statuses: " + status);
        }
        return ActivityEvent.of("validation:" + v.id() + ":finished:" + v.currentAttempt(), v.projectId(), VALIDATION_FINISHED,
                        ActivityTargetType.VALIDATION, v.id())
                .withTaskId(v.taskId())
                .withResult("PASS".equals(status) ? ActivityResult.SUCCESS : ActivityResult.FAILURE);
    }
}
