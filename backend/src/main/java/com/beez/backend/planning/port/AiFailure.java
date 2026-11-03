package com.beez.backend.planning.port;

import com.beez.backend.changeproposal.port.AiCallException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * AI 호출 실패. {@code retryable} 은 같은 요청을 다시 보내면 성공할 수 있는지다 — 시간 초과·연결 실패·5xx 는 true,
 * BE 가 만든 요청이 AI 에 거부된 4xx(입력 오류)는 false 다. 둘 다 사용자에게는 공통 오류(code·message)만 나간다.
 */
public class AiFailure extends AiCallException {
    private final boolean retryable;

    public AiFailure(HttpStatus status, String code, String message, List<Map<String, Object>> usage, boolean retryable) {
        super(status, code, message, usage);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
