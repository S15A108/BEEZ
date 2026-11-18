package com.beez.backend.contextaccess.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.contextaccess.dto.ContextAccessLogResponse;
import com.beez.backend.contextaccess.entity.ContextAccessLog;
import com.beez.backend.contextaccess.repository.ContextAccessLogRepository;
import com.beez.backend.contextaccess.repository.ContextAccessLogSpecifications;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CTX-A06 컨텍스트 조회 이력 목록. 팀장(사람 세션)만. 프로젝트·작업·도구 필터는 페이지를 자르기 전에 건다 (공통 3절).
 * 커서는 작업 목록과 같은 방식: id 내림차순, cursor = 이전 페이지 마지막 id.
 */
@Service
public class ContextAccessLogQueryService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final ProjectAccessChecker access;
    private final ContextAccessLogRepository logs;

    public ContextAccessLogQueryService(ProjectAccessChecker access, ContextAccessLogRepository logs) {
        this.access = access;
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public PageResponse<ContextAccessLogResponse> list(CallerContext caller, Long projectId, String taskId, String tool,
                                                       String cursor, Integer limit) {
        access.requireHumanLeader(caller, projectId);
        Long taskFilter = Ids.parse(taskId, "task_id");
        Long cursorId = parseCursor(cursor);
        int size = normalizeLimit(limit);

        Specification<ContextAccessLog> spec = ContextAccessLogSpecifications.projectIs(projectId)
                .and(ContextAccessLogSpecifications.taskIs(taskFilter))
                .and(ContextAccessLogSpecifications.toolIs(tool))
                .and(ContextAccessLogSpecifications.idBefore(cursorId));
        // 한 개 더 읽어서 다음 페이지가 있는지 본다
        List<ContextAccessLog> rows = logs.findAll(spec, PageRequest.of(0, size + 1, Sort.by(Sort.Direction.DESC, "id")))
                .getContent();
        boolean hasMore = rows.size() > size;
        List<ContextAccessLog> page = rows;
        if (hasMore) {
            page = rows.subList(0, size);
        }
        List<ContextAccessLogResponse> items = new ArrayList<>();
        for (ContextAccessLog log : page) {
            items.add(ContextAccessLogResponse.from(log));
        }
        String nextCursor = null;
        if (hasMore) {
            nextCursor = Ids.str(page.get(page.size() - 1).getId());
        }
        return PageResponse.of(items, hasMore, nextCursor);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit;
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "cursor 형식이 올바르지 않습니다");
        }
    }
}
