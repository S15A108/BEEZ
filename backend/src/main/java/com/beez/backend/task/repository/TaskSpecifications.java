package com.beez.backend.task.repository;

import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import org.springframework.data.jpa.domain.Specification;

/** 작업 목록 조회 조건 조각. null 이면 "조건 없음"을 뜻하는 Specification 을 돌려준다. */
public final class TaskSpecifications {

    private TaskSpecifications() {
    }

    public static Specification<Task> projectIs(Long projectId) {
        return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
    }

    public static Specification<Task> statusIs(TaskStatus status) {
        if (status == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Task> assigneeIs(Long assigneeMemberId) {
        if (assigneeMemberId == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("assigneeMemberId"), assigneeMemberId);
    }

    /** 미리 골라 둔 작업 번호 안에서만 (validation_status 필터) */
    public static Specification<Task> idIn(java.util.List<Long> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    /** 커서 = 이전 페이지 마지막 id. 그보다 작은 것만 */
    public static Specification<Task> idBefore(Long cursor) {
        if (cursor == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.lessThan(root.get("id"), cursor);
    }
}
