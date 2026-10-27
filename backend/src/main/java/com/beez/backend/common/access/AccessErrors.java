package com.beez.backend.common.access;

import com.beez.backend.common.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 접근 거부 응답을 한 곳에 모은다 (-172 합의 3, 참고 2).
 * 검사 순서: 인증 실패 401 → 프로젝트 없음·비멤버 404 → 다른 프로젝트 객체·남의 PRIVATE 404 → 역할·담당자 조건 403.
 * 404 는 사유와 관계없이 같은 code·message 로 응답하고, 실제 사유(reason)는 서버 로그에만 남긴다.
 * 403 은 code {@code forbidden} 하나에 사용자가 알아야 할 사유를 메시지로 담는다 — 같은 프로젝트 멤버에게만 가는 응답이라 숨기지 않는다.
 * 매핑이 바뀌면 이 파일만 고친다.
 */
public final class AccessErrors {

    public static final String UNAUTHENTICATED_MESSAGE = "세션 또는 에이전트 인증이 유효하지 않음";
    public static final String NOT_FOUND_MESSAGE = "리소스가 없거나 프로젝트 소속·접근 권한이 없음";
    /** 필터 단계 403 처럼 사유를 알 수 없을 때만 쓰는 기본 문구 */
    public static final String FORBIDDEN_MESSAGE = "해당 역할·소유 조건을 충족하지 않음";

    private static final Logger log = LoggerFactory.getLogger("com.beez.access");

    private AccessErrors() {
    }

    /** 401. 세션 없음·만료, 에이전트 토큰 무효, 에이전트 토큰으로 /api/** 호출. */
    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", UNAUTHENTICATED_MESSAGE);
    }

    /** 401. 폐기됐거나 호출 계정의 것이 아닌 에이전트 토큰. reason 은 로그에만. */
    public static ApiException tokenRevoked(String reason) {
        log.info("access denied status=401 reason={}", reason);
        return new ApiException(HttpStatus.UNAUTHORIZED, "token_revoked", UNAUTHENTICATED_MESSAGE);
    }

    /**
     * 404. 존재를 숨겨야 하는 모든 거부. reason 예: project_missing, not_member, cross_project, private_record.
     * 객체 소속 확인 예: {@code repo.findByIdAndProjectId(id, projectId).orElseThrow(() -> AccessErrors.notFound("cross_project"))}
     */
    public static ApiException notFound(String reason) {
        log.info("access denied status=404 reason={}", reason);
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", NOT_FOUND_MESSAGE);
    }

    /**
     * 403. 같은 프로젝트 멤버이지만 팀장·담당자·작성자·사람 조건을 충족하지 않음 (-172 합의 3).
     *
     * @param reason  로그용 사유 (예: "role_required_leader project=3 account=7")
     * @param message 응답 메시지 — 사용자가 알아야 할 사유 (예: "팀장만 할 수 있습니다")
     */
    public static ApiException forbidden(String reason, String message) {
        log.info("access denied status=403 reason={}", reason);
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
