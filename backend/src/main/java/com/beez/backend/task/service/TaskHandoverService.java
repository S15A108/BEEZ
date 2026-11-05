package com.beez.backend.task.service;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.notification.NotificationRequest;
import com.beez.backend.notification.NotificationRequester;
import com.beez.backend.notification.NotificationTargetType;
import com.beez.backend.notification.event.NotificationMessages;
import com.beez.backend.notification.event.NotificationMessages.Message;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 담당자 인계 (S15P31A108-212). 팀원 제외·나가기(MEM-A09·A10)의 열린 작업 일괄 인계와 팀장의 담당자 변경(TSK-A06)이
 * 같은 규칙({@link #reassign})을 쓴다. 권한·멤버십 확인은 부르는 쪽이 끝낸다.
 * <p>
 * 점유 중(IN_PROGRESS·WAITING)인 작업은 이전 담당자 쪽에서 요청한 검증을 쓰지 못하게 하고,
 * IN_PROGRESS 는 WAITING(REVALIDATION) 으로 내린 뒤 넘긴다 — 새 담당자는 RESUME 때 G1 에서 새 검증을 받는다
 * (04-task-lifecycle.md §6 "진행 중 담당자 변경: WAITING 으로 내린 뒤", 2026-10-29 전진 결정). WAITING 의 다른 사유는 덮어쓰지 않는다.
 * <p>
 * 검증 무효화는 {@link ValidationStatusLookup#invalidateForReassign} 를 쓴다. 진행 중 계획 변경(S2, markStaleForTask)과 달리
 * 계획 리비전이 그대로라 도는(RUNNING) 검증이 늦게 PASS 가 되면 쓸 수 있게 되므로 그것까지 막는다. 완료 보고에서 서버가 접수하는
 * DELTA 검증만 담당자와 무관해 그대로 둔다.
 * <p>
 * 담당자가 실제로 바뀐 작업마다 {@code TASK_ASSIGNEE_CHANGED} 활동과 새 담당자에게 {@code TASK_ASSIGNED} 알림을 같은 트랜잭션에서
 * 남긴다 (S15P31A108-288, 계약 docs/design/10-notification-activity-contract.md 6.1·6.2·7절). 호출 지점은 이 한 곳이다.
 */
@Service
public class TaskHandoverService {

    /** 검증을 무효로 만든 이유 (validation.stale_reason) */
    static final String STALE_REASON = "ASSIGNEE_CHANGED";

    /** 활동 유형 (계약 6.2) */
    static final String ACTIVITY_ASSIGNEE_CHANGED = "TASK_ASSIGNEE_CHANGED";

    /** 알림 유형 (계약 6.1). 새 담당자에게, 행위자 본인이면 보내지 않는다 */
    static final String NOTIFICATION_ASSIGNED = "TASK_ASSIGNED";

    private static final Set<TaskStatus> OPEN = EnumSet.of(
            TaskStatus.DRAFT, TaskStatus.READY, TaskStatus.IN_PROGRESS, TaskStatus.WAITING);

    private final TaskRepository tasks;
    private final ValidationStatusLookup validations;
    private final JdbcClient jdbc;
    private final ActivityRecorder activities;
    private final NotificationRequester notifications;

    public TaskHandoverService(TaskRepository tasks, ValidationStatusLookup validations, JdbcClient jdbc,
                               ActivityRecorder activities, NotificationRequester notifications) {
        this.tasks = tasks;
        this.validations = validations;
        this.jdbc = jdbc;
        this.activities = activities;
        this.notifications = notifications;
    }

    /**
     * fromMemberId 의 열린 작업을 toMemberId 에게 넘긴다. 열린 작업이 있는데 toMemberId 가 없으면 409 active_tasks_exist.
     * 멤버십 종료와 같은 트랜잭션이어야 해서 바깥 트랜잭션을 요구한다.
     *
     * @param caller     제외(팀장)·나가기(본인)를 요청한 사람. 활동 행위자이고, 새 담당자가 이 사람이면 알림을 보내지 않는다
     * @param toMemberId 같은 프로젝트의 활성 멤버, fromMemberId 아님 (부르는 쪽이 확인). 열린 작업이 없으면 null 이어도 된다
     * @return 넘긴 작업 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int handOver(CallerContext caller, long projectId, long fromMemberId, Long toMemberId) {
        List<Task> open = tasks.findByProjectIdAndAssigneeMemberIdAndStatusIn(projectId, fromMemberId, OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        if (toMemberId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "active_tasks_exist",
                    "정리되지 않은 열린 작업이 " + open.size() + "개 있습니다. 인계 대상을 지정하세요");
        }
        List<Changed> changed = new ArrayList<>();
        for (Task task : open) {
            apply(task, toMemberId, changed);
        }
        // 동시에 상태가 바뀐 작업이 있으면 lock_version 이 달라 409 revision_conflict 로 전체가 되돌아간다
        tasks.saveAllAndFlush(open);
        recordChanged(caller, changed, toMemberId);
        return open.size();
    }

    /**
     * 열린 작업 하나의 담당자를 바꾸고 저장(flush)한 뒤 활동·알림을 남긴다. lock_version 비교는 이 flush 에서 일어난다
     * (다르면 409 revision_conflict). 같은 담당자로 바꾸면 아무것도 하지 않는다(상태·검증·활동·알림 모두). 닫힌 작업은 부르는 쪽이 거른다.
     *
     * @param caller 담당자를 바꾼 사람(TSK-A06 팀장). 활동 행위자이고, 새 담당자가 이 사람이면 알림을 보내지 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reassign(CallerContext caller, Task task, long toMemberId) {
        List<Changed> changed = new ArrayList<>(1);
        apply(task, toMemberId, changed);
        if (changed.isEmpty()) {
            return;
        }
        tasks.saveAndFlush(task);
        recordChanged(caller, changed, toMemberId);
    }

    /** 담당자가 실제로 바뀐 작업 하나와 바꾸기 전 잠금 버전 */
    private record Changed(Task task, int lockBefore) {
    }

    /** 담당자를 바꾼다(저장 전). 같은 담당자면 아무것도 하지 않고, 바꿨으면 changed 에 더한다 */
    private void apply(Task task, long toMemberId, List<Changed> changed) {
        if (task.getAssigneeMemberId() != null && task.getAssigneeMemberId() == toMemberId) {
            return;
        }
        // 키는 변경 전 잠금 버전(계약 6.2 예외) — 담당자 변경마다 버전이 오르므로 다음 변경과 겹치지 않는다
        int lockBefore = task.getLockVersion();
        if (task.getStatus().isOccupied()) {
            validations.invalidateForReassign(task.getId(), STALE_REASON);
        }
        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            task.holdForRevalidation();
        }
        task.changeAssignee(toMemberId);
        changed.add(new Changed(task, lockBefore));
    }

    /**
     * 바뀐 작업마다 활동 하나, 새 담당자에게 알림 하나(본인이면 생략). <b>작업을 저장소로 flush 한 뒤에</b> 부른다 — 활동·알림 입구는
     * 네이티브 쿼리라 실행 전에 영속성 컨텍스트를 flush 하는데, 거기서 잠금 충돌이 나면 저장소 예외 변환을 거치지 않아
     * 409(ObjectOptimisticLockingFailureException)가 아니라 500(jakarta OptimisticLockException)이 된다.
     */
    private void recordChanged(CallerContext caller, List<Changed> changed, long toMemberId) {
        if (changed.isEmpty()) {
            return;
        }
        long toAccountId = accountOf(toMemberId);
        // 자기에게 배정한 경우(팀장 자기 배정 등)는 자기가 고른 일이라 알리지 않는다 (계약 6.1, 11-04 결정)
        boolean notify = !Objects.equals(toAccountId, caller.accountId());
        List<NotificationRequest> requests = new ArrayList<>(changed.size());
        for (Changed c : changed) {
            Task task = c.task();
            String key = "task:" + task.getId() + ":lock:" + c.lockBefore() + ":ASSIGNEE_CHANGED";
            activities.record(caller, ActivityEvent.of(key, task.getProjectId(), ACTIVITY_ASSIGNEE_CHANGED,
                    ActivityTargetType.TASK, task.getId()));
            if (notify) {
                Message message = NotificationMessages.taskAssigned(task.getTitle());
                requests.add(new NotificationRequest(key + ":to:" + toAccountId, task.getProjectId(), toAccountId,
                        NOTIFICATION_ASSIGNED, message.title(), message.body(), NotificationTargetType.TASK, task.getId(),
                        null));
            }
        }
        // 활성 멤버 확인을 한 번에 (requestAll 은 프로젝트마다 조회 한 번)
        notifications.requestAll(requests);
    }

    /**
     * 새 담당자의 계정. 부르는 쪽이 활성 멤버임을 이미 확인했다. project 패키지가 이 서비스를 쓰므로 그쪽 저장소를 끌어오지 않고
     * SQL 로 읽는다(패키지 순환 방지). 이 트랜잭션에서 그 멤버 행은 바뀌지 않아 flush 되지 않은 변경을 놓칠 일이 없다.
     */
    private long accountOf(long memberId) {
        return jdbc.sql("select account_id from project_member where id = :m").param("m", memberId)
                .query(Long.class).single();
    }
}
