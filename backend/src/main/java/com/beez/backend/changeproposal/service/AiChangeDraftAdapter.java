package com.beez.backend.changeproposal.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.changeproposal.port.ChangeDraftGateway;
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
 * 504·읽기 시간 초과 → 504 ai_timeout, 502·연결 실패(연결 시간 초과 포함)·그 밖의 5xx → 502 ai_unavailable.
 * 그 밖의 4xx(422 invalid_ai_input 등)는 BE 가 만든 요청이 거부된 것이라 사용자가 고칠 수 없다 — 502 로 알리고 원인은 로그에 남긴다.
 *
 * <p>AI 라우트 없음(404)은 일시 장애가 아니라 AI 서버에 초안 기능(AI-A06)이 아직 올라가지 않은 것이다. 다시 눌러도 같으므로
 * "잠시 뒤 다시 시도"가 아니라 503 change_draft_disabled "아직 사용할 수 없어요"로 알린다(S15P31A108-415, 역할 분담 추천 -405 와 같은 방식).
 *
 * <p>모델 호출이 일어난 뒤 실패했으면 AI 가 오류 본문에 실제 {@code usage[]} 를 실을 수 있다(03-ai-internal 공통 규칙).
 * 그 배열을 예외({@link AiCallException})에 실어 보내 호출한 쪽이 사용량을 남기게 한다. 응답 자체를 받지 못했으면 null 이다.
 */
@Component
public class AiChangeDraftAdapter implements ChangeDraftGateway {
    private static final Logger log = LoggerFactory.getLogger(AiChangeDraftAdapter.class);
    static final String UNAVAILABLE = "문서 수정 초안을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.";
    static final String TIMEOUT = "초안 생성이 시간 안에 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요.";
    static final String DISABLED_CODE = "change_draft_disabled";
    static final String DISABLED = "비비 초안은 아직 사용할 수 없어요.";

    private final AiClient ai;

    public AiChangeDraftAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> draft(Map<String, Object> input) {
        try {
            return ai.beeDraft(input);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            log.warn("AI-A06 timed out: {}", e.toString());
            throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, AiErrorBodies.usageIn(e));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // 모델을 부르지 않았으므로 사용량이 없고, 같은 요청을 다시 보내도 결과가 같다(retryable=false)
                log.warn("AI-A06 route is not deployed on the AI server");
                throw new AiCallException(HttpStatus.SERVICE_UNAVAILABLE, DISABLED_CODE, DISABLED, null, false);
            }
            if (!e.getStatusCode().is5xxServerError()) {
                log.error("AI-A06 rejected the request: status={} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, AiErrorBodies.usageIn(e));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            // JDK 의 연결 시간 초과(HttpConnectTimeoutException)는 HttpTimeoutException 을 상속하므로 먼저 걸러 연결 실패로 본다
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                log.warn("AI-A06 timed out: {}", e.toString());
                throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, null);
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 Map 으로 읽지 못함
            log.warn("AI-A06 response body was not readable: {}", e.toString());
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        }
    }
}
