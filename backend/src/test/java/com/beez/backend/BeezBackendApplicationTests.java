package com.beez.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * 실제 Postgres(pgvector) 에서 컨텍스트 로드 + Flyway 마이그레이션, 그리고 전역 설정이 실제 HTTP 응답에 반영되는지 확인한다.
 * 느린 층이므로 "integration" 태그 — `./gradlew test` 에서는 제외되고 `./gradlew integrationTest` 로만 돈다.
 */
class BeezBackendApplicationTests extends IntegrationTestSupport {

    private HttpResponse<String> get(String path) {
        return client().get(path);
    }

    private ApiClient loggedIn() {
        return client().login(fixtures().emailOf(fixtures().account()), AccessFixtures.PASSWORD);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void ping_returnsSnakeCaseJson_andRequestIdHeader() {
        HttpResponse<String> res = get("/api/ping");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"ok\":true").contains("\"started_at\"").contains("\"server_time\"");
        assertThat(res.body()).doesNotContain("startedAt");
        assertThat(res.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void unknownApiPath_returnsErrorEnvelope_401BeforeLogin_404After() {
        HttpResponse<String> anonymous = get("/api/does-not-exist");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"error\"").contains("\"code\":\"unauthenticated\"");

        HttpResponse<String> res = loggedIn().get("/api/does-not-exist");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("\"error\"").contains("\"code\":\"not_found\"");
    }

    @Test
    void systemStatus_isOk_evenWhenAiServerIsDown() {
        HttpResponse<String> res = loggedIn().get("/api/system/status");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"ok\":true").contains("\"ai_reachable\":false");
    }

    @Test
    void actuatorHealth_isUp() {
        HttpResponse<String> res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void openApiDocs_areServed() {
        HttpResponse<String> res = get("/v3/api-docs");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"title\":\"BEEZ API\"").contains("/api/ping");
    }
}
