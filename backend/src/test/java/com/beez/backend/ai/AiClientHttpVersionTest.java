package com.beez.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.config.BeezProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercise real loopback HTTP, without an external LLM or provider credentials. */
class AiClientHttpVersionTest {
    @Test
    void validateUsesHttp11WithoutH2cUpgrade() throws Exception {
        var observedVersion = new AtomicReference<String>();
        var observedUpgrade = new AtomicReference<String>();
        var observedBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/validate", exchange -> {
            observedVersion.set(exchange.getProtocol());
            observedUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            observedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"origin\":\"TEST\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var ai = new BeezProperties.Ai("http://127.0.0.1:" + server.getAddress().getPort(),
                    "synthetic-token", Duration.ofSeconds(2), Duration.ofSeconds(2));
            var client = new AiClient(new BeezProperties(null, ai, null));
            assertThat(client.validatePlan(Map.of("project_id", "synthetic"))).containsEntry("origin", "TEST");
            assertThat(observedVersion.get()).isEqualTo("HTTP/1.1");
            assertThat(observedUpgrade.get()).isNull();
            assertThat(observedBody.get()).contains("synthetic");
        } finally {
            server.stop(0);
        }
    }
}
