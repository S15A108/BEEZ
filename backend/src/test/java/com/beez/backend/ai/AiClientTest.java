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
    @ValueSource(strings = {"/v1/document-reviews", "/v1/document-chunks", "/v1/embeddings", "/v1/impact"})
    void postsAuthenticatedJsonAndReadsEnvelope(String endpoint) {
        var input = Map.<String, Object>of("document_id", "194", "content", "검토할 문서");
        Map<String, Object> result = switch (endpoint) {
            case "/v1/document-reviews" -> client.documentReview(input);
            case "/v1/document-chunks" -> client.documentChunks(input);
            case "/v1/impact" -> client.changeImpact(input);
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
