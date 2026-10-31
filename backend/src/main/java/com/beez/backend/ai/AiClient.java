package com.beez.backend.ai;

import com.beez.backend.config.BeezProperties;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FastAPI AI 서버 호출 전용 클라이언트.
 * 원칙: ai 는 텍스트·임베딩·실행 증거만 돌려준다. 판정·상태 변경은 절대 이 응답으로 결정하지 않는다.
 * 모델 호출이 실패하면 호출한 쪽이 템플릿으로 폴백하고 origin 을 정직하게 기록한다 (프로토타입 llm.ts 와 동일).
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final RestClient rest;

    public AiClient(BeezProperties props) {
        BeezProperties.Ai ai = props.ai();
        // Uvicorn's HTTP/1.1 server does not support the JDK client's cleartext
        // HTTP/2 upgrade (h2c). Keep internal BE→AI traffic on HTTP/1.1.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(ai.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(ai.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(ai.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.internalToken())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    /** ai 서버 생존 확인. 실패해도 예외를 던지지 않는다 (ai 가 꺼져 있어도 backend 는 동작해야 한다). */
    public boolean ping() {
        try {
            rest.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("ai server unreachable: {}", e.toString());
            return false;
        }
    }

    /** 이후 lead/review/embeddings 호출이 공통으로 쓸 RestClient. */
    protected RestClient rest() {
        return rest;
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentChunks(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-chunks").body(input).retrieve().body(java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentEmbeddings(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/embeddings").body(input).retrieve().body(java.util.Map.class);
    }

    /** 작업계획 검증은 오류를 숨기거나 템플릿으로 대체하지 않는다. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> validatePlan(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/validate").body(input).retrieve().body(java.util.Map.class);
    }

    /** BE adapter contract; AI feature implementation belongs to the AI owner. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentReview(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-reviews").body(input).retrieve().body(java.util.Map.class);
    }
}
