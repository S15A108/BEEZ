package com.beez.backend.common.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 클라이언트에 그대로 전달되는 예외. 프로토타입의 HttpError(status, message, code) 에 대응한다.
 * 예: throw new ApiException(HttpStatus.FORBIDDEN, "human_only", "설계 확정은 사람만 할 수 있습니다");
 * 명세가 error.details 를 정한 오류는 details 를 함께 넘긴다 (예: ACC-A12 remaining_attempts).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 없으면 null. */
    public Map<String, Object> getDetails() {
        return details;
    }
}
