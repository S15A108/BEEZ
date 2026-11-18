package com.beez.backend.task.entity;

import java.io.Serializable;
import java.util.Objects;

/** task_dependency 의 복합 기본키 (task_id, depends_on_task_id). */
public class TaskDependencyId implements Serializable {

    private Long taskId;
    private Long dependsOnTaskId;

    public TaskDependencyId() {
    }

    public TaskDependencyId(Long taskId, Long dependsOnTaskId) {
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskDependencyId that = (TaskDependencyId) o;
        return Objects.equals(taskId, that.taskId) && Objects.equals(dependsOnTaskId, that.dependsOnTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, dependsOnTaskId);
    }
}
