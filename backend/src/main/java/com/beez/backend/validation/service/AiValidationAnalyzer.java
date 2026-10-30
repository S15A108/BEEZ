package com.beez.backend.validation.service;

import com.beez.backend.ai.AiClient;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * AI 서버의 {@code /v1/validate} 를 부른다. 실패를 규약의 분류로 바꾼다:
 * 504·읽기 시간 초과 → ai_timeout, 502·연결 실패(연결 시간 초과 포함)·그 밖의 5xx → ai_unavailable,
 * 404(AI 서버에 검증 경로가 아직 없음)도 호출 실패로 보아 ai_unavailable,
 * 200 인데 본문을 읽지 못함(JSON 객체가 아님 등) → ai_invalid_output, 4xx(422 등) → BE 가 만든 요청이 거부된 것이므로
 * validation_execution_failed.
 */
/* 서버용 가짜 분석기(beez.validation.fake-analyzer=true)를 켜면 이 클래스 대신 FakeValidationAnalyzer 가 쓰인다 */
@Component
@ConditionalOnProperty(name = "beez.validation.fake-analyzer", havingValue = "false", matchIfMissing = true)
public class AiValidationAnalyzer implements ValidationAnalyzer {

    private final AiClient ai;

    public AiValidationAnalyzer(AiClient ai) {
        this.ai = ai;
    }

    @Override
    public Map<String, Object> analyze(Map<String, Object> request) throws AnalyzerException {
        try {
            return ai.validate(request);
        } catch (HttpServerErrorException.GatewayTimeout e) {
            throw new AnalyzerException("ai_timeout", "AI 분석이 시간 안에 끝나지 않았습니다 (504)", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 404) {
                throw new AnalyzerException("ai_unavailable", "AI 서버 오류 " + e.getStatusCode().value(), e);
            }
            throw new AnalyzerException("validation_execution_failed", "AI 가 요청을 거부했습니다 " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패와 읽기 시간 초과가 모두 여기로 온다.
            // JDK 의 연결 시간 초과(HttpConnectTimeoutException)는 HttpTimeoutException 을 상속하므로 먼저 걸러 연결 실패로 본다
            Throwable cause = e.getCause();
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AnalyzerException("ai_unavailable", "AI 서버에 연결하지 못했습니다 (연결 시간 초과)", e);
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                throw new AnalyzerException("ai_timeout", "AI 호출이 시간 안에 응답하지 않았습니다", e);
            }
            throw new AnalyzerException("ai_unavailable", "AI 서버에 연결하지 못했습니다", e);
        } catch (RestClientException e) {
            // 응답은 받았지만 본문을 Map 으로 읽지 못함 (JSON 배열·문자열, 모르는 형식 등) → 출력 계약 위반
            throw new AnalyzerException("ai_invalid_output", "AI 응답 본문을 읽지 못했습니다", e);
        } catch (RuntimeException e) {
            throw new AnalyzerException("validation_execution_failed", "AI 호출 중 오류: " + e.getMessage(), e);
        }
    }
}
