package com.beez.backend.validation.api;

import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.validation.dto.AcceptResult;
import com.beez.backend.validation.dto.AcceptValidationRequest;
import com.beez.backend.validation.dto.RetryValidationRequest;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.service.ValidationAcceptService;
import com.beez.backend.validation.service.ValidationQueryService;
import com.beez.backend.validation.service.ValidationRetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 작업 검증 API (S15P31A108-159). 재시도(VAL-A04)는 S15P31A108-161 */
@RestController
@Tag(name = "Validations", description = "작업 계획 검증 접수·결과·이력")
public class ValidationController {

    private final ValidationAcceptService accept;
    private final ValidationQueryService queries;
    private final ValidationRetryService retries;

    public ValidationController(ValidationAcceptService accept, ValidationQueryService queries, ValidationRetryService retries) {
        this.accept = accept;
        this.queries = queries;
        this.retries = retries;
    }

    /** 규칙만으로 끝났으면 200, 의미 분석을 큐에 넣었으면 202 (VAL-A01 응답 규칙) */
    @PostMapping("/api/projects/{projectId}/tasks/{taskId}/validations")
    @Operation(summary = "VAL-A01 작업 검증 접수")
    public ResponseEntity<ValidationResponse> accept(CallerContext caller, @PathVariable Long projectId, @PathVariable Long taskId,
                                                     @Valid @RequestBody AcceptValidationRequest body) {
        AcceptResult result = accept.accept(caller, projectId, taskId, Ids.parse(body.planRevisionId(), "plan_revision_id"),
                body.expectedLockVersion());
        HttpStatus status = HttpStatus.ACCEPTED;
        if (result.finished()) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status).body(result.validation());
    }

    /**
     * 실패한 검증을 같은 입력으로 다시 돌린다. 늘 202 — 같은 expected_attempt 로 다시 보내면 그때 연 시도가 있는 지금 검증을 돌려준다.
     * Idempotency-Key 헤더는 쓰지 않는다 (시도 번호가 요청의 식별값)
     */
    @PostMapping("/api/projects/{projectId}/validations/{validationId}/retry")
    @Operation(summary = "VAL-A04 검증 실행 재시도")
    public ResponseEntity<ValidationResponse> retry(CallerContext caller, @PathVariable Long projectId, @PathVariable Long validationId,
                                                    @Valid @RequestBody RetryValidationRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(retries.retry(caller, projectId, validationId, body.expectedAttempt()));
    }

    @GetMapping("/api/projects/{projectId}/validations/{validationId}")
    @Operation(summary = "VAL-A02 검증 결과 조회")
    public ValidationResponse get(CallerContext caller, @PathVariable Long projectId, @PathVariable Long validationId) {
        return queries.get(caller, projectId, validationId);
    }

    @GetMapping("/api/projects/{projectId}/tasks/{taskId}/validations")
    @Operation(summary = "VAL-A03 작업 검증 이력")
    public PageResponse<ValidationResponse> list(CallerContext caller, @PathVariable Long projectId, @PathVariable Long taskId,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return queries.list(caller, projectId, taskId, cursor, limit);
    }
}
