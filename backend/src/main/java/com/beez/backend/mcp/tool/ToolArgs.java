package com.beez.backend.mcp.tool;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import org.springframework.http.HttpStatus;

/** 도구 인수 → 서비스 인수 변환에서 함께 쓰는 것. 서비스가 문자열 ID 대신 long 을 받는 도구만 쓴다 */
final class ToolArgs {

    private ToolArgs() {
    }

    /** 필수 ID 인수. 없으면 400 validation_failed, 형식이 틀리면 {@link Ids#parse} 의 400 */
    static long requiredId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", field + " 는 필수입니다");
        }
        return Ids.parse(raw, field);
    }
}
