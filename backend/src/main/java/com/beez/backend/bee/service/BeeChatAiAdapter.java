package com.beez.backend.bee.service;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.bee.port.BeeChatGateway;
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
 * {@link AiClient#beeChat} 를 부르고 실패를 규약의 분류로 바꾼다 ({@code AiChangeDraftAdapter} 와 같은 규칙):
 * 504·읽기 시간 초과 → 504 ai_timeout, 502·연결 실패·그 밖의 5xx·AI 라우트 없음(404) → 502 ai_unavailable.
 * 401(내부 토큰)·422(요청 계약 위반)·503(bee_chat_not_configured)은 사용자가 고칠 수 없는 설정·계약 오류라
 * 502 로 알리되 원인(상태·코드)을 error 로그에 남긴다. 질문·근거 원문은 로그에 쓰지 않는다.
 * 모델 호출이 일어난 뒤 실패했으면 AI 가 오류 본문에 실은 {@code usage[]} 를 예외에 실어 호출한 쪽이 기록하게 한다.
 */
@Component
public class BeeChatAiAdapter implements BeeChatGateway {
    private static final Logger log = LoggerFactory.getLogger(BeeChatAiAdapter.class);
    static final String UNAVAILABLE = "비비가 지금 답을 만들지 못했어요. 잠시 뒤 다시 질문해 주세요.";
    static final String TIMEOUT = "비비의 답이 시간 안에 끝나지 않았어요. 잠시 뒤 다시 질문해 주세요.";

    private final AiClient ai;

    public BeeChatAiAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> chat(Map<String, Object> input) {
        try {
            return ai.beeChat(input);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            log.warn("AI-A05 timed out: {}", e.toString());
            throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, AiErrorBodies.usageIn(e));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 503 || status == 401 || status == 422) {
                // 서버 설정(BEE_CHAT_ENABLED·GMS_KEY·내부 토큰)이나 BE 요청 계약 문제다. 본문에 요청 내용이 있을 수 있어 상태만 남긴다.
                log.error("AI-A05 rejected the request: status={} (503 이면 AI 서버의 BEE_CHAT_ENABLED·GMS_KEY 를 확인)", status);
            } else if (!e.getStatusCode().is5xxServerError() && status != 404) {
                log.error("AI-A05 rejected the request: status={}", status);
            } else {
                log.warn("AI-A05 failed: status={}", status);
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, AiErrorBodies.usageIn(e));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            // JDK 의 연결 시간 초과(HttpConnectTimeoutException)는 HttpTimeoutException 을 상속하므로 먼저 걸러 연결 실패로 본다
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                log.warn("AI-A05 timed out: {}", e.toString());
                throw new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, null);
            }
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 Map 으로 읽지 못함
            log.warn("AI-A05 response body was not readable: {}", e.toString());
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null);
        }
    }
}
