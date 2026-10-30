package com.beez.backend.validation.service;

import com.beez.backend.ai.AiClient;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * AI 서버의 {@code /v1/validate} 를 부른다. 실패를 규약의 분류로 바꾼다:
 * 504·읽기 시간 초과 → ai_timeout, 502·연결 실패·그 밖의 5xx → ai_unavailable, 4xx(422 등) → ai_invalid_output 이 아니라
 * BE 가 만든 요청이 거부된 것이므로 validation_execution_failed.
 */
@Component
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
            if (e.getStatusCode().is5xxServerError()) {
                throw new AnalyzerException("ai_unavailable", "AI 서버 오류 " + e.getStatusCode().value(), e);
            }
            throw new AnalyzerException("validation_execution_failed", "AI 가 요청을 거부했습니다 " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패와 읽기 시간 초과가 모두 여기로 온다. 원인이 시간 초과면 ai_timeout
            Throwable cause = e.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                throw new AnalyzerException("ai_timeout", "AI 호출이 시간 안에 응답하지 않았습니다", e);
            }
            throw new AnalyzerException("ai_unavailable", "AI 서버에 연결하지 못했습니다", e);
        } catch (RuntimeException e) {
            throw new AnalyzerException("validation_execution_failed", "AI 호출 중 오류: " + e.getMessage(), e);
        }
    }
}
