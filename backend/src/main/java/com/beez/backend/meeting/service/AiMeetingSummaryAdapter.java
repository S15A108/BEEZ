package com.beez.backend.meeting.service;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.meeting.port.MeetingSummaryGateway;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link AiClient#meetingAnalysis} 를 부르고 실패를 규약의 분류로 바꾼다 (문서 수정 초안 어댑터와 같은 규칙):
 * 504·읽기 시간 초과 → 504 ai_timeout, 502·연결 실패(연결 시간 초과 포함)·그 밖의 5xx·AI 라우트 없음(404) → 502 ai_unavailable.
 * 그 밖의 4xx(422 invalid_ai_input 등)는 BE 가 만든 요청이 거부된 것이라 사용자가 고칠 수 없다 — 502 로 알리고 원인은 로그에 남긴다.
 * AI 서버가 SUMMARY 계약을 아직 받지 않으면(2026-11-05 기준 "SUMMARY contract is not agreed") 4xx 로 거절되어 502 가 된다.
 *
 * <p>모델 호출이 일어난 뒤 실패했으면 AI 가 오류 본문에 실제 {@code usage[]} 를 실을 수 있다. 그 배열을
 * {@link AiCallException} 에 실어 호출한 쪽이 사용량을 남기게 한다. 응답 자체를 받지 못했으면 null 이다.
 */
@Component
public class AiMeetingSummaryAdapter implements MeetingSummaryGateway {
    private static final Logger log = LoggerFactory.getLogger(AiMeetingSummaryAdapter.class);
    static final String UNAVAILABLE = "회의를 정리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
    static final String TIMEOUT = "회의 정리가 시간 안에 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요.";

    private final AiClient ai;

    public AiMeetingSummaryAdapter(AiClient ai) {
        this.ai = ai;
    }

    @Override
    public Map<String, Object> summarize(Map<String, Object> input) {
        try {
            return ai.meetingAnalysis(input);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            log.warn("AI-A08 timed out: {}", e.toString());
            throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, AiErrorBodies.usageIn(e));
        } catch (RestClientResponseException e) {
            if (!e.getStatusCode().is5xxServerError() && e.getStatusCode().value() != 404) {
                log.error("AI-A08 rejected the request: status={} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, AiErrorBodies.usageIn(e));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            // JDK 의 연결 시간 초과(HttpConnectTimeoutException)는 HttpTimeoutException 을 상속하므로 먼저 걸러 연결 실패로 본다
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                log.warn("AI-A08 timed out: {}", e.toString());
                throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, null);
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 Map 으로 읽지 못함
            log.warn("AI-A08 response body was not readable: {}", e.toString());
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        }
    }
}
