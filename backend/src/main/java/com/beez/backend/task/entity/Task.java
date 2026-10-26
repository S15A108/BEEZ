package com.beez.backend.task.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 작업. 테이블 {@code task}.
 *
 * <p>다른 도메인(프로젝트·멤버·검증)은 번호(Long)로만 가리킨다. 소유 관계는 DB 복합 FK 가 보장한다.
 * 상태 변경은 setter 가 아니라 아래 메서드로만 한다. 전이 허용 여부는 서비스(TaskTransitionPolicy)가 먼저 검사한다.
 */
@Entity
@Table(name = "task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 표시 키. 프로젝트 안에서 유일 (T-1, T-2 …). 서버가 발급 */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "assignee_member_id")
    private Long assigneeMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "waiting_reason")
    private WaitingReason waitingReason;

    @Column(name = "current_plan_revision_id")
    private Long currentPlanRevisionId;

    /** 시작을 허용한 검증. IN_PROGRESS·WAITING·COMPLETED 면 DB CHECK 로 NOT NULL */
    @Column(name = "started_validation_id")
    private Long startedValidationId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * 낙관적 잠금 번호. JPA 가 UPDATE 때 {@code WHERE lock_version = ?} 를 자동으로 붙이고,
     * 다르면 ObjectOptimisticLockingFailureException → 409 revision_conflict 로 변환된다.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    public static Task create(Long projectId, String key, String title, Long assigneeMemberId) {
        Task task = new Task();
        task.projectId = projectId;
        task.key = key;
        task.title = title;
        task.assigneeMemberId = assigneeMemberId;
        task.status = TaskStatus.DRAFT;
        return task;
    }

    // ---- 계획 ----

    /** 새 계획 리비전이 현재 계획이 된다. DRAFT 였으면 READY 로. 제목도 리비전 것으로 맞춘다. */
    public void applyNewPlanRevision(Long revisionId, String newTitle) {
        this.currentPlanRevisionId = revisionId;
        this.title = newTitle;
        if (this.status == TaskStatus.DRAFT) {
            this.status = TaskStatus.READY;
        }
    }

    /** 진행 중(점유 중)에 계획이 바뀌면 재검증 대기. 점유는 유지한다 (명세 TSK-A04). */
    public void holdForRevalidation() {
        this.status = TaskStatus.WAITING;
        this.waitingReason = WaitingReason.REVALIDATION;
    }

    // ---- 담당 ----

    public void changeAssignee(Long newAssigneeMemberId) {
        this.assigneeMemberId = newAssigneeMemberId;
    }

    // ---- 상태 전이 (S15P31A108-158 에서 사용) ----

    public void start(Long validationId, Instant now) {
        this.status = TaskStatus.IN_PROGRESS;
        this.startedValidationId = validationId;
        this.waitingReason = null;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    public void resume(Long validationId) {
        this.status = TaskStatus.IN_PROGRESS;
        this.startedValidationId = validationId;
        this.waitingReason = null;
    }

    /** 이름을 wait 로 두지 않은 이유: Object.wait 와 헷갈린다. */
    public void holdFor(WaitingReason reason) {
        this.status = TaskStatus.WAITING;
        this.waitingReason = reason;
    }

    public void complete(Instant now) {
        this.status = TaskStatus.COMPLETED;
        this.waitingReason = null;
        this.completedAt = now;
    }

    public void cancel(Instant now) {
        this.status = TaskStatus.CANCELLED;
        this.waitingReason = null;
        this.cancelledAt = now;
    }
}
