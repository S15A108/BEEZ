package com.beez.backend.changeproposal.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.port.ChangeDraftGateway;
import com.beez.backend.common.api.ApiException;
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
 * {@link AiClient#beeDraft} 를 부르고 실패를 규약의 분류로 바꾼다 (검증 쪽 {@code AiValidationAnalyzer} 와 같은 규칙):
 * 504·읽기 시간 초과 → 504 ai_timeout, 502·연결 실패(연결 시간 초과 포함)·그 밖의 5xx·AI 라우트 없음(404) → 502 ai_unavailable.
 * 그 밖의 4xx(422 invalid_ai_input 등)는 BE 가 만든 요청이 거부된 것이라 사용자가 고칠 수 없다 — 502 로 알리고 원인은 로그에 남긴다.
 */
@Component
public class AiChangeDraftAdapter implements ChangeDraftGateway {
    private static final Logger log = LoggerFactory.getLogger(AiChangeDraftAdapter.class);
    static final String UNAVAILABLE = "문서 수정 초안을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.";
    static final String TIMEOUT = "초안 생성이 시간 안에 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요.";

    private final AiClient ai;

    public AiChangeDraftAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> draft(Map<String, Object> input) {
        try {
            return ai.beeDraft(input);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            throw timeout(e);
        } catch (RestClientResponseException e) {
            if (!e.getStatusCode().is5xxServerError() && e.getStatusCode().value() != 404) {
                log.error("AI-A06 rejected the request: status={} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            // JDK 의 연결 시간 초과(HttpConnectTimeoutException)는 HttpTimeoutException 을 상속하므로 먼저 걸러 연결 실패로 본다
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                throw timeout(e);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 Map 으로 읽지 못함
            log.warn("AI-A06 response body was not readable: {}", e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE);
        }
    }

    private static ApiException timeout(Exception cause) {
        log.warn("AI-A06 timed out: {}", cause.toString());
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT);
    }
}
