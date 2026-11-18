package com.beez.backend.planning.service;

import static com.beez.backend.ai.AiErrorBodies.usageIn;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.planning.port.AiFailure;
import com.beez.backend.planning.port.DesignDraftGateway;
import com.beez.backend.planning.port.PlanningQuestionGateway;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 133·134 AI 라우트를 부르고 실패를 규약의 분류로 바꾼다(233 {@code AiChangeDraftAdapter} 와 같은 규칙):
 * 504·읽기 시간 초과 → 504 ai_timeout, 502·연결 실패·그 밖의 5xx·라우트 없음/비활성(404·503) → 502 ai_unavailable(재시도 가능),
 * 그 밖의 4xx(400·422 등 BE 가 만든 요청이 거부됨)는 502 ai_unavailable 이되 재시도해도 같으므로 retryable=false.
 * 모델 호출 뒤 실패면 AI 가 오류 본문에 실은 {@code usage[]} 를 예외에 실어 호출한 쪽이 사용량을 남기게 한다.
 */
@Component
public class AiPlanningAdapter implements PlanningQuestionGateway, DesignDraftGateway {
    private static final Logger log = LoggerFactory.getLogger(AiPlanningAdapter.class);
    static final String UNAVAILABLE = "AI 서버가 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
    /** AI 가 출력 상한에 닿아 답이 끊겼다고 알린 경우(S15P31A108-382). */
    static final String OUTPUT_TOO_LONG = "AI가 답을 쓰다가 출력 길이 상한에 걸려 끊겼어요. 문서가 길거나 질문이 많을 때 생겨요.";
    static final String TIMEOUT = "AI 응답이 시간 안에 오지 않았어요. 잠시 뒤 다시 시도해 주세요.";

    private final AiClient ai;

    public AiPlanningAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> generate(Map<String, Object> input) { return call("planning-questions", ai::planningQuestions, input); }

    @Override
    public Map<String, Object> draft(Map<String, Object> input) { return call("design-drafts", ai::designDrafts, input); }

    /** AI 호출 실패 분류. 작업 계획 문서 초안(S15P31A108-385)도 같은 규칙을 쓰므로 공개한다. */
    public static Map<String, Object> call(String label, Function<Map<String, Object>, Map<String, Object>> route, Map<String, Object> input) {
        try {
            return route.apply(input);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            log.warn("AI {} timed out: {}", label, e.toString());
            throw new AiFailure(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, usageIn(e), true);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 출력 상한에 닿아 끊긴 답은 형식 오류와 달리 이유를 알린다. 상한을 올리면 성공할 수 있어 다시 시도할 수 있다.
            if ("model_output_truncated".equals(AiErrorBodies.codeIn(e))) {
                log.warn("AI {} output was cut off by the output token limit", label);
                throw new AiFailure(HttpStatus.BAD_GATEWAY, "ai_output_too_long", OUTPUT_TOO_LONG, usageIn(e), true);
            }
            // 404(라우트 없음)·503(기본 비활성)은 설정이 갖춰지면 성공하므로 재시도 가능, 나머지 4xx 는 요청 자체가 거부된 것이다.
            boolean retryable = e.getStatusCode().is5xxServerError() || status == 404 || status == 408 || status == 429;
            if (!retryable) log.error("AI {} rejected the request: status={} body={}", label, status, e.getResponseBodyAsString());
            throw new AiFailure(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, usageIn(e), retryable);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AiFailure(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null, true);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                log.warn("AI {} timed out: {}", label, e.toString());
                throw new AiFailure(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", TIMEOUT, null, true);
            }
            throw new AiFailure(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null, true);
        } catch (RestClientException e) {
            log.warn("AI {} response body was not readable: {}", label, e.toString());
            throw new AiFailure(HttpStatus.BAD_GATEWAY, "ai_unavailable", UNAVAILABLE, null, true);
        }
    }
}
