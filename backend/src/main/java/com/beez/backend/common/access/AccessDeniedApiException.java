package com.beez.backend.common.access;

import com.beez.backend.common.api.ApiException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * {@link AccessErrors} 가 만든 접근 거부(401·403·404). 응답 본문·상태는 {@link ApiException} 과 똑같고,
 * 서버만 아는 거부 사유의 <b>종류</b>를 함께 들고 간다 — MCP 감사 기록(mcp_audit_log.deny_reason, -177)이 거부와 일반 실패를 가르는 데 쓴다.
 *
 * <p>ApiException 에 칸을 더하지 않고 하위 클래스로 둔 이유: 사유는 접근 거부에만 있고, ApiException 을 직접 만드는 다른 오류(검증·충돌)와 섞이지 않게 한다.
 */
public class AccessDeniedApiException extends ApiException {

    /** 사유 종류는 소문자·숫자·밑줄만. 로그용 사유 문자열의 첫 낱말(예: "not_member project=3 account=7" → not_member) */
    private static final Pattern KIND = Pattern.compile("[a-z0-9_]{1,64}");

    private final String reasonKind;

    AccessDeniedApiException(HttpStatus status, String code, String message, String reason) {
        super(status, code, message);
        this.reasonKind = kindOf(reason, code);
    }

    /**
     * 거부 사유의 종류. 번호(프로젝트·계정·토큰 ID)는 떼고 첫 낱말만 남긴다 — 감사 행에 넣어도 다른 식별자가 새지 않는다.
     * 사유가 없거나 형식이 다르면 응답 code 를 쓴다 (예: 인증 실패 → unauthenticated).
     */
    public String reasonKind() {
        return reasonKind;
    }

    static String kindOf(String reason, String code) {
        if (reason == null || reason.isBlank()) {
            return code;
        }
        String first = reason.strip().split("\\s+", 2)[0];
        return KIND.matcher(first).matches() ? first : code;
    }
}
