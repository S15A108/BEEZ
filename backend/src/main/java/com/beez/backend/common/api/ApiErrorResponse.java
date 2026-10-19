package com.beez.backend.common.api;

/** 오류 응답 본문: { "error": { "code": "...", "message": "..." } } */
public record ApiErrorResponse(Error error) {

    public record Error(String code, String message) {
    }

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new Error(code, message));
    }
}
