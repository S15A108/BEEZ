package com.beez.backend.task.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 작업 사이의 <b>현재</b> 선행 관계. 테이블 {@code task_dependency}.
 * 계획 리비전을 새로 올리면 그 계획의 선행 목록으로 덮어쓴다. 리비전 당시 스냅샷은 task_plan_revision.dependency_task_ids.
 */
@Entity
@Table(name = "task_dependency")
@IdClass(TaskDependencyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDependency extends BaseTimeEntity {

    @Id
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Id
    @Column(name = "depends_on_task_id", nullable = false)
    private Long dependsOnTaskId;

    /** 두 작업이 같은 프로젝트인지 DB 복합 FK 가 확인하는 데 쓴다 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private DependencyKind kind;

    public TaskDependency(Long projectId, Long taskId, Long dependsOnTaskId, DependencyKind kind) {
        this.projectId = projectId;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.kind = kind;
    }
}
