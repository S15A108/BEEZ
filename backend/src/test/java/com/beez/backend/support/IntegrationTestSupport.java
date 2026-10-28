package com.beez.backend.support;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 느린 층 통합 테스트의 공통 바탕: 실제 Postgres(pgvector) + 실제 포트. Docker 필요.
 * 컨테이너는 JVM 당 하나만 띄워 모든 통합 테스트 클래스가 나눠 쓴다 (테스트 사이에 DB 를 비우지 않으므로
 * 이메일 등 유일 값은 {@link AccessFixtures#uniqueEmail()} 로 만든다).
 * <pre>
 * class TaskServiceIntegrationTest extends IntegrationTestSupport {
 *     &#64;Test void ... {
 *         var p = fixtures().projectWithLeader();
 *         ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
 *         ...
 *     }
 * }
 * </pre>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d").asCompatibleSubstituteFor("postgres"));

    static {
        postgres.start();
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    /** 쿠키(세션)를 따로 가지는 새 HTTP 클라이언트. 사용자마다 하나씩 만든다. */
    protected ApiClient client() {
        return new ApiClient(Integer.parseInt(env.getRequiredProperty("local.server.port")));
    }

    /** 계정·프로젝트·멤버를 SQL 로 바로 넣는다. -173/-174 API 가 생기기 전에도 권한 시나리오를 만들 수 있다. */
    protected AccessFixtures fixtures() {
        return new AccessFixtures(jdbc, passwordEncoder);
    }
}
