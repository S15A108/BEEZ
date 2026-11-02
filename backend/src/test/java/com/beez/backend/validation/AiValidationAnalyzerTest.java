package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.validation.service.AiValidationAnalyzer;
import com.beez.backend.validation.service.ValidationAnalyzer;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** AI 호출 실패 → 공통 "검증 실행 오류 규약"의 error_code 분류 (S15P31A108-159) */
class AiValidationAnalyzerTest {

    private String codeFor(RuntimeException failure) {
        AiClient ai = mock(AiClient.class);
        when(ai.validate(any())).thenThrow(failure);
        try {
            new AiValidationAnalyzer(ai).analyze(Map.of());
        } catch (ValidationAnalyzer.AnalyzerException e) {
            return e.errorCode();
        }
        throw new AssertionError("분류된 실패로 바뀌지 않았다: " + failure);
    }

    private static HttpClientErrorException client(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(), new byte[0], null);
    }

    @Test
    void readTimeoutAnd504_areTimeout() {
        assertThat(codeFor(new ResourceAccessException("read", new HttpTimeoutException("read timed out")))).isEqualTo("ai_timeout");
        assertThat(codeFor(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout", new HttpHeaders(),
                new byte[0], null))).isEqualTo("ai_timeout");
    }

    @Test
    void connectTimeout_isUnavailable_notTimeout() {
        // HttpConnectTimeoutException 은 HttpTimeoutException 을 상속하지만 연결 실패다
        assertThat(codeFor(new ResourceAccessException("connect", new HttpConnectTimeoutException("connect timed out"))))
                .isEqualTo("ai_unavailable");
    }

    @Test
    void serverErrorAndMissingEndpoint_areUnavailable() {
        assertThat(codeFor(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                new byte[0], null))).isEqualTo("ai_unavailable");
        // AI 서버에 검증 경로가 아직 없음
        assertThat(codeFor(client(HttpStatus.NOT_FOUND))).isEqualTo("ai_unavailable");
    }

    private ValidationAnalyzer.AnalyzerException failureFor(RuntimeException failure) {
        AiClient ai = mock(AiClient.class);
        when(ai.validate(any())).thenThrow(failure);
        try {
            new AiValidationAnalyzer(ai).analyze(Map.of());
        } catch (ValidationAnalyzer.AnalyzerException e) {
            return e;
        }
        throw new AssertionError("분류된 실패로 바뀌지 않았다: " + failure);
    }

    @Test
    void usageInErrorBody_isCarried_andMalformedBodyIsIgnored() {
        // 모델을 부른 뒤 504 — 오류 본문에 사용량이 있다 (AI-A02 오류 규약)
        String body = "{\"error\":{\"code\":\"timeout\",\"message\":\"x\"},"
                + "\"usage\":[{\"request_key\":\"llm-validate-1-attempt-1-call-1\",\"input_tokens\":7,\"tokens_known\":true}]}";
        ValidationAnalyzer.AnalyzerException timeout = failureFor(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT,
                "Gateway Timeout", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(timeout.errorCode()).isEqualTo("ai_timeout");
        assertThat(timeout.usage()).hasSize(1);
        assertThat(timeout.usage().get(0).get("request_key")).isEqualTo("llm-validate-1-attempt-1-call-1");

        // 본문이 없거나 JSON 이 아니거나 usage 가 배열이 아니면 사용량 없이 분류만 한다
        assertThat(failureFor(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                "upstream down".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).usage()).isNull();
        assertThat(failureFor(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(),
                "{\"usage\":{\"input_tokens\":1}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).usage()).isNull();
        assertThat(failureFor(client(HttpStatus.NOT_FOUND)).usage()).isNull();
    }

    @Test
    void rejectedRequest_isExecutionFailure_andUnreadableBody_isInvalidOutput() {
        assertThat(codeFor(client(HttpStatus.BAD_REQUEST))).isEqualTo("validation_execution_failed");
        assertThat(codeFor(new RestClientException("Error while extracting response"))).isEqualTo("ai_invalid_output");
    }
}
