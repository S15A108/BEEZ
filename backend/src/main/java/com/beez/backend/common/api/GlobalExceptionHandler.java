package com.beez.backend.common.api;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadSize(Exception e) {
        return ResponseEntity.status(413).body(ApiErrorResponse.of("file_too_large", "파일 1 MiB·요청 전체 2 MiB를 초과했습니다."));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> handleMissingInput(Exception e) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("validation_failed", "필수 요청 값이 누락되었습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다");
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("validation_failed", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("invalid_json", "요청 본문 JSON 형식이 올바르지 않습니다"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("not_found", "없는 API 경로입니다"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponse.of("method_not_allowed", "지원하지 않는 HTTP 메서드입니다"));
    }

    /** @Version 충돌: 내가 읽은 뒤 누군가 먼저 바꿨다. 명세 00-common 5절 → 409 revision_conflict */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("revision_conflict", "다른 요청이 먼저 변경했습니다. 다시 조회한 뒤 시도하세요"));
    }

    /** DB 제약(FK·UNIQUE·CHECK) 위반. 서비스가 미리 거르지 못한 잘못된 참조 → 400. 500 으로 새지 않게 한다 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException e) {
        String constraint = null;
        String sqlState = null;
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(e);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            if (cause instanceof ConstraintViolationException violation && constraint == null) {
                constraint = violation.getConstraintName();
            }
            if (cause instanceof SQLException sql) {
                if (sqlState == null) sqlState = sql.getSQLState();
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (cause instanceof PSQLException postgres && postgres.getServerErrorMessage() != null && constraint == null) {
                constraint = postgres.getServerErrorMessage().getConstraint();
            }
            if (cause.getCause() != null) pending.add(cause.getCause());
        }
        log.warn("데이터 무결성 제약 위반: exception_type={}, constraint_name={}, sql_state={}",
                e.getMostSpecificCause().getClass().getSimpleName(), diagnostic(constraint), diagnostic(sqlState));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("validation_failed", "참조하는 데이터가 없거나 제약 조건에 어긋납니다"));
    }

    private static String diagnostic(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$-]{1,128}") ? value : "unknown";
    }

    /** 쿼리 파라미터 타입 불일치 (예: status=FOO) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("validation_failed", e.getName() + " 값이 올바르지 않습니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외가 발생했습니다.", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("internal", "서버 내부 오류"));
    }
}
