package com.beez.backend.common.api;

import java.util.Map;

/**
 * 오류 응답 본문: { "error": { "code": "...", "message": "...", "details": { ... } } }.
 * details 는 명세가 정한 오류만 채운다 (예: ACC-A12 remaining_attempts). 없으면 non_null 설정으로 빠진다.
 */
public record ApiErrorResponse(Error error) {

    public record Error(String code, String message, Map<String, Object> details) {
    }

    public static ApiErrorResponse of(String code, String message) {
        return of(code, message, null);
    }

    public static ApiErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(new Error(code, message, details));
    }
}
