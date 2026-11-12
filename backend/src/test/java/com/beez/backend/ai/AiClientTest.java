package com.beez.backend.ai;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.config.BeezProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real HTTP client against a local stub; no AI server or provider key is needed. */
class AiClientTest {
    private HttpServer server;
    private AiClient client;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> protocol = new AtomicReference<>();
    private final AtomicReference<String> upgrade = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"origin\":\"MODEL\",\"result\":{},\"usage\":[]}";

    @BeforeEach void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            protocol.set(exchange.getProtocol());
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new AiClient(new BeezProperties(null, new BeezProperties.Ai(
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token",
                Duration.ofMillis(500), Duration.ofSeconds(2)), null));
    }

    @AfterEach void stopStub() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/document-reviews", "/v1/document-chunks", "/v1/embeddings", "/v1/impact",
            "/v1/document-revision-blocks", "/v1/document-revisions", "/v1/bee/assignments", "/v1/bee/task-breakdown"})
    void postsAuthenticatedJsonAndReadsEnvelope(String endpoint) {
        var input = Map.<String, Object>of("document_id", "194", "content", "검토할 문서");
        Map<String, Object> result = switch (endpoint) {
            case "/v1/document-reviews" -> client.documentReview(input);
            case "/v1/document-chunks" -> client.documentChunks(input);
            case "/v1/impact" -> client.changeImpact(input);
            case "/v1/document-revision-blocks" -> client.revisionBlocks(input);
            case "/v1/document-revisions" -> client.documentRevision(input);
            case "/v1/bee/assignments" -> client.assignmentRecommendations(input);
            case "/v1/bee/task-breakdown" -> client.taskBreakdown(input);
            default -> client.documentEmbeddings(input);
        };
        assertThat(path.get()).isEqualTo(endpoint);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
        assertThat(upgrade.get()).isNull();
        assertThat(authorization.get()).isEqualTo("Bearer fixture-token");
        assertThat(JsonMapper.builder().build().readValue(body.get(), Map.class)).isEqualTo(input);
        assertThat(result).containsEntry("origin", "MODEL").containsKeys("result", "usage");
    }

    @Test void documentReviewAndTaskBreakdownUseTheirOwnReadTimeouts() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            byte[] bytes = "{\"origin\":\"MODEL\",\"result\":{},\"usage\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var slow = new AiClient(new BeezProperties(null, new BeezProperties.Ai(
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token",
                Duration.ofMillis(500), Duration.ofMillis(300)), null),
                // 질문 300ms·초안 3초·문서 검토 대화 300ms·작업 분해 300ms. 첫 검토는 워커 기본 제한(120초)을 쓴다(S15P31A108-391).
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofMillis(300));
        var input = Map.<String, Object>of("document_id", "382");
        // 검토 워커는 긴 전용 제한(기본 120초)을 쓰고, 대화는 nginx 안의 짧은 제한을 쓴다(S15P31A108-391).
        assertThat(slow.documentReview(input)).containsEntry("origin", "MODEL");
        assertThatThrownBy(() -> slow.documentReviewChat(input))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThat(slow.designDrafts(input)).containsEntry("origin", "MODEL");
        assertThatThrownBy(() -> slow.taskBreakdown(input))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThatThrownBy(() -> slow.documentChunks(input))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    /** 영향 분석은 공통 읽기 제한(20초)이 아니라 AI 모델 제한(60초)보다 긴 전용 제한을 쓴다. 공통값이면 정상 분석을 먼저 끊는다 (S15P31A108-402). */
    @Test void changeImpactUsesItsOwnLongerReadTimeoutThanOtherCalls() throws Exception {
        server.removeContext("/");
        AtomicReference<String> seenPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            byte[] bytes = "{\"origin\":\"MODEL\",\"result\":{},\"usage\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var slow = new AiClient(new BeezProperties(null, new BeezProperties.Ai(
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token",
                Duration.ofMillis(500), Duration.ofMillis(300)), null),
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300),
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofSeconds(3));
        var input = Map.<String, Object>of("request_key", "impact-1");
        assertThat(slow.changeImpact(input)).containsEntry("origin", "MODEL");
        assertThat(seenPath.get()).isEqualTo("/v1/impact");
        assertThatThrownBy(() -> slow.documentChunks(input))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        // 공통 제한보다 짧게 설정해도 공통값 아래로 내려가지 않는다(lease 계산도 같은 값을 쓴다)
        var ai = new BeezProperties.Ai("http://x", "t", Duration.ofSeconds(3), Duration.ofSeconds(20));
        assertThat(AiClient.changeImpactReadTimeout(ai, Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(20));
        assertThat(AiClient.changeImpactReadTimeout(ai, Duration.ofSeconds(75))).isEqualTo(Duration.ofSeconds(75));
    }

    /** 작업 계획 문서 초안도 공통 읽기 제한보다 긴 전용 제한을 쓰고, 경로·본문은 AI 계약 그대로 보낸다 (S15P31A108-385). */
    @Test void taskPlanDraftsUsesItsOwnLongerReadTimeoutThanOtherCalls() throws Exception {
        server.removeContext("/");
        AtomicReference<String> seenPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            byte[] bytes = "{\"origin\":\"MODEL\",\"result\":{},\"usage\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var slow = new AiClient(new BeezProperties(null, new BeezProperties.Ai(
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token",
                Duration.ofMillis(500), Duration.ofMillis(300)), null),
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300),
                Duration.ofSeconds(3));
        var input = Map.<String, Object>of("request_key", "task-plan-draft-1");
        assertThat(slow.taskPlanDrafts(input)).containsEntry("origin", "MODEL");
        assertThat(seenPath.get()).isEqualTo("/v1/task-plan-drafts");
        assertThatThrownBy(() -> slow.documentChunks(input))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void unavailableReviewEndpointPreservesHttpFailure(int errorStatus) {
        status = errorStatus;
        response = "{\"detail\":\"AI review unavailable\"}";
        assertThatThrownBy(() -> client.documentReview(Map.of("document_id", "194")))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(errorStatus));
    }

    @Test void healthSuccessAndFailureDoNotRequireLiveAi() {
        assertThat(client.ping()).isTrue();
        assertThat(path.get()).isEqualTo("/health");
        assertThat(method.get()).isEqualTo("GET");
        status = 503;
        assertThat(client.ping()).isFalse();
        server.stop(0);
        assertThat(client.ping()).isFalse();
    }
}
