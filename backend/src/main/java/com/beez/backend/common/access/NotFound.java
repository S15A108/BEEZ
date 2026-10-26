package com.beez.backend.common.access;

import com.beez.backend.common.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 404 응답을 한 모양으로 통일한다 (-172 합의안 "404 메시지는 하나, 실제 사유는 서버 로그").
 * 다른 프로젝트 것·없는 번호·삭제된 프로젝트·비멤버를 응답만 보고는 구분할 수 없게 한다 (AC-10).
 */
public final class NotFound {

    public static final String MESSAGE = "요청한 대상을 찾을 수 없습니다";

    private static final Logger log = LoggerFactory.getLogger(NotFound.class);

    private NotFound() {
    }

    /**
     * @param reason 로그에만 남는 실제 사유. 예: "task_not_in_project task=5 project=2"
     */
    public static ApiException because(String reason) {
        log.info("not_found {}", reason);
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", MESSAGE);
    }
}
