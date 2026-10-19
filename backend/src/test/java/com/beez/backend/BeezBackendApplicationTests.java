package com.beez.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Postgres(pgvector) 에서 컨텍스트 로드 + Flyway 마이그레이션, 그리고 전역 설정이 실제 HTTP 응답에 반영되는지 확인한다.
 * Boot 4 에서 테스트 전용 어노테이션 패키지가 많이 바뀌어, 의도적으로 JDK HttpClient 로 실제 포트를 때린다. Docker 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class BeezBackendApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        String port = env.getProperty("local.server.port");
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void contextLoads() {
    }

    @Test
    void ping_returnsSnakeCaseJson_andRequestIdHeader() throws Exception {
        HttpResponse<String> res = get("/api/ping");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"ok\":true").contains("\"started_at\"").contains("\"server_time\"");
        assertThat(res.body()).doesNotContain("startedAt");
        assertThat(res.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void unknownApiPath_returnsErrorEnvelope() throws Exception {
        HttpResponse<String> res = get("/api/does-not-exist");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("\"error\"").contains("\"code\":\"not_found\"");
    }

    @Test
    void systemStatus_isOk_evenWhenAiServerIsDown() throws Exception {
        HttpResponse<String> res = get("/api/system/status");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"ok\":true").contains("\"ai_reachable\":false");
    }

    @Test
    void actuatorHealth_isUp() throws Exception {
        HttpResponse<String> res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void openApiDocs_areServed() throws Exception {
        HttpResponse<String> res = get("/v3/api-docs");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"title\":\"BEEZ API\"").contains("/api/ping");
    }
}
