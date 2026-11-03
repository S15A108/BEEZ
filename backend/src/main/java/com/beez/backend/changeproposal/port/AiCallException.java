package com.beez.backend.changeproposal.port;

import com.beez.backend.common.api.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * AI 호출 실패를 응답용 오류로 바꾼 예외. AI 가 오류 본문에 실은 {@code usage[]}(모델 호출이 일어난 뒤 실패했을 때의 실제 사용량)를
 * 함께 들고 있어, 호출한 쪽이 알려진 토큰·비용을 사용량 표에 남기고 본문을 받지 못한 경우에만 "미확인"으로 기록할 수 있다.
 * 외부 응답에는 공통 오류(code·message)만 나간다.
 */
public class AiCallException extends ApiException {
    private final List<Map<String, Object>> usage;

    /** @param usage 오류 본문의 usage 배열. 본문이 없거나 usage 가 없거나 읽을 수 없으면 null */
    public AiCallException(HttpStatus status, String code, String message, List<Map<String, Object>> usage) {
        super(status, code, message);
        this.usage = usage;
    }

    public List<Map<String, Object>> usage() { return usage; }
}
