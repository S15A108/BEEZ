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

    @Test
    void rejectedRequest_isExecutionFailure_andUnreadableBody_isInvalidOutput() {
        assertThat(codeFor(client(HttpStatus.BAD_REQUEST))).isEqualTo("validation_execution_failed");
        assertThat(codeFor(new RestClientException("Error while extracting response"))).isEqualTo("ai_invalid_output");
    }
}
