package com.beez.backend.contextaccess.repository;

import com.beez.backend.contextaccess.entity.ContextAccessLog;
import org.springframework.data.jpa.domain.Specification;

/** 이력 목록 조건 조각. null 이면 조건 없음 */
public final class ContextAccessLogSpecifications {

    private ContextAccessLogSpecifications() {
    }

    public static Specification<ContextAccessLog> projectIs(Long projectId) {
        return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
    }

    /** 다른 프로젝트 작업 번호를 넣으면 조건이 맞는 행이 없어 0건이 된다 (-172 합의 3) */
    public static Specification<ContextAccessLog> taskIs(Long taskId) {
        if (taskId == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("taskId"), taskId);
    }

    public static Specification<ContextAccessLog> toolIs(String tool) {
        if (tool == null || tool.isBlank()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("tool"), tool);
    }

    public static Specification<ContextAccessLog> idBefore(Long cursor) {
        if (cursor == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.lessThan(root.get("id"), cursor);
    }
}
