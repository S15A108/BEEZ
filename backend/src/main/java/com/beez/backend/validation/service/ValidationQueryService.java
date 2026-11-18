package com.beez.backend.validation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VAL-A02 검증 결과 조회, VAL-A03 작업 검증 이력. 조회는 새 검증·모델 호출을 만들지 않는다 */
@Service
public class ValidationQueryService {

    private final ProjectAccessChecker access;
    private final ValidationRepository validations;
    private final TaskRepository tasks;
    private final ValidationResponses responses;

    public ValidationQueryService(ProjectAccessChecker access, ValidationRepository validations, TaskRepository tasks,
                                  ValidationResponses responses) {
        this.access = access;
        this.validations = validations;
        this.tasks = tasks;
        this.responses = responses;
    }

    @Transactional(readOnly = true)
    public ValidationResponse get(CallerContext caller, Long projectId, Long validationId) {
        access.requireMember(caller, projectId);
        ValidationRow row = validations.find(projectId, validationId)
                .orElseThrow(() -> AccessErrors.notFound("validation_missing project=" + projectId + " validation=" + validationId));
        return responses.toResponse(row);
    }

    /** 최신순 커서 페이지 (커서 = 마지막 검증 ID) */
    @Transactional(readOnly = true)
    public PageResponse<ValidationResponse> list(CallerContext caller, Long projectId, Long taskId, String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        if (tasks.findByIdAndProjectId(taskId, projectId).isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        int size = PageResponse.normalizeLimit(limit);
        long before = PageResponse.beforeId(cursor);
        List<ValidationRow> rows = validations.listByTask(projectId, taskId, before, size + 1);
        return PageResponse.fromRows(rows, size, ValidationRow::id, page -> {
            List<ValidationResponse> out = new ArrayList<>();
            for (ValidationRow row : page) {
                out.add(responses.toResponse(row));
            }
            return out;
        });
    }
}
