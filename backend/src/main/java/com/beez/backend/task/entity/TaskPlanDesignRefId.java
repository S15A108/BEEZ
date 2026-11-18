package com.beez.backend.task.entity;

import java.io.Serializable;
import java.util.Objects;

/** task_plan_design_ref 의 복합 기본키 (plan_revision_id, design_item_id). JPA 는 복합키 클래스에 equals/hashCode 를 요구한다. */
public class TaskPlanDesignRefId implements Serializable {

    private Long planRevisionId;
    private Long designItemId;

    public TaskPlanDesignRefId() {
    }

    public TaskPlanDesignRefId(Long planRevisionId, Long designItemId) {
        this.planRevisionId = planRevisionId;
        this.designItemId = designItemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskPlanDesignRefId that = (TaskPlanDesignRefId) o;
        return Objects.equals(planRevisionId, that.planRevisionId) && Objects.equals(designItemId, that.designItemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planRevisionId, designItemId);
    }
}
