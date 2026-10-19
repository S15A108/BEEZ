package com.beez.backend.common.api;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에 그대로 전달되는 예외. 프로토타입의 HttpError(status, message, code) 에 대응한다.
 * 예: throw new ApiException(HttpStatus.FORBIDDEN, "human_only", "설계 확정은 사람만 할 수 있습니다");
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
