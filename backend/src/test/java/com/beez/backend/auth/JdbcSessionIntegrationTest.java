package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.BeezBackendApplication;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import tools.jackson.databind.ObjectMapper;

/** 웹 세션 DB 저장 (S15P31A108-242 완료 조건). 세션이 backend 메모리가 아니라 spring_session 테이블에 있다. */
class JdbcSessionIntegrationTest extends IntegrationTestSupport {

    @Autowired
    JdbcConnectionDetails database;   // Testcontainers DB. 두 번째 backend 도 같은 DB 에 붙인다

    @Autowired
    JdbcTemplate db;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessions;

    @Autowired
    JdbcIndexedSessionRepository jdbcSessions;

    @Autowired
    ObjectMapper json;

    /**
     * 깨지면: 배포(backend 컨테이너 재생성) 때마다 웹 사용자가 다시 로그아웃되거나, 운영 쿠키 속성이 바뀐다.
     * 로그인을 받은 backend 를 내린 뒤, 그 로그인을 본 적 없는 다른 backend 가 같은 쿠키를 받아 준다.
     */
    @Test
    void sessionIssuedByStoppedBackend_isAcceptedByAnotherBackend_andCookieKeepsItsAttributes() {
        long accountId = fixtures().account();
        String email = fixtures().emailOf(accountId);

        String setCookie;
        // 운영처럼 Secure 를 켠 두 번째 backend. 같은 DB 를 쓴다.
        try (ConfigurableApplicationContext previous = startAnotherBackend(Map.of(
                "server.servlet.session.cookie.secure", "true"))) {
            HttpResponse<String> login = ApiClient.forPort(port(previous)).post("/api/auth/login",
                    "{\"email\":\"" + email + "\",\"password\":\"" + AccessFixtures.PASSWORD + "\"}");
            assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
            setCookie = login.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith("BEEZ_SESSION=")).findFirst().orElseThrow();
        }

        // 메모리 세션 시절과 같은 속성: 이름·HttpOnly·SameSite=Lax·Secure(운영)·Path=/, 브라우저 종료까지(Max-Age 없음)
        assertThat(setCookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Secure").contains("Path=/")
                .doesNotContainIgnoringCase("Max-Age").doesNotContainIgnoringCase("Expires");
        // 만료 2h 는 DB 행에 그대로 들어간다
        assertThat(db.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE principal_name = ?",
                Integer.class, Long.toString(accountId))).isEqualTo(7200);

        // Secure 쿠키라 http 시험 클라이언트의 쿠키 저장소는 보내지 않는다. 브라우저처럼 직접 싣는다
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        HttpResponse<String> me = client().get("/api/me", "Cookie", cookie);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"" + email + "\"");
    }

    /**
     * 깨지면: 세션이 있는 채로 다시 로그인할 때 세션 ID 가 바뀌지 않아(세션 고정 방지 실패) 로그인 전 ID 가 계속 통하거나,
     * ID 교체가 새 행을 만들어 그 사용자의 세션이 쌓인다. AuthController.login 의 changeSessionId 가 DB 세션에서도 지켜지는지 본다.
     */
    @Test
    void reLoginWithExistingSession_changesSessionId_andOldIdStopsWorking() {
        var p = fixtures().projectWithLeader();
        String body = "{\"email\":\"" + p.leaderEmail() + "\",\"password\":\"" + AccessFixtures.PASSWORD + "\"}";
        ApiClient user = client();

        String before = sessionCookie(user.post("/api/auth/login", body));
        String after = sessionCookie(user.post("/api/auth/login", body));   // 쿠키 저장소가 before 를 싣고 간다

        assertThat(after).isNotEqualTo(before);
        assertThat(client().get("/api/me", "Cookie", before).statusCode()).as("로그인 전 세션 ID").isEqualTo(401);
        assertThat(user.get("/api/me").statusCode()).isEqualTo(200);
        assertThat(rowsOf(Long.toString(p.leaderAccountId()))).as("ID 만 바뀌고 행은 하나").isEqualTo(1);
    }

    /** 깨지면: 만료된 세션 행이 DB 에 계속 쌓인다 (cleanup-cron 이 부르는 정리 작업). */
    @Test
    void expiredSession_isRemovedFromDb_byCleanup() {
        var p = fixtures().projectWithLeader();
        var user = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String principal = Long.toString(p.leaderAccountId());
        assertThat(rowsOf(principal)).isEqualTo(1);

        db.update("UPDATE spring_session SET expiry_time = 0 WHERE principal_name = ?", principal);
        jdbcSessions.cleanUpExpiredSessions();

        assertThat(rowsOf(principal)).isZero();
        assertThat(user.get("/api/me").statusCode()).isEqualTo(401);
    }

    /** 깨지면: -171(탈퇴·비밀번호 변경)이 그 사용자의 다른 세션을 찾아 끊을 수 없다. principal_name 이 계정 ID 여야 한다. */
    @Test
    void sessionsOfAnAccount_canBeFoundByAccountId_andDeleted() {
        var p = fixtures().projectWithLeader();
        var laptop = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        var phone = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        var found = sessions.findByPrincipalName(Long.toString(p.leaderAccountId()));
        assertThat(found).hasSize(2);

        found.keySet().forEach(sessions::deleteById);

        assertThat(laptop.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(phone.get("/api/me").statusCode()).isEqualTo(401);
    }

    /** 깨지면: 에이전트가 MCP 를 부를 때마다 세션 행이 생긴다 (/mcp 체인은 무상태). */
    @Test
    void mcp_withValidToken_createsNoSession() {
        var p = fixtures().projectWithLeader();
        var leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String token = json.readTree(leader.post("/api/projects/" + p.projectId() + "/agent-tokens", "{}").body())
                .get("token").asString();
        long requestedAt = System.currentTimeMillis();

        HttpResponse<String> init = client().post("/mcp", """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                "Accept", "application/json, text/event-stream", "Authorization", "Bearer " + token);

        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        assertThat(init.headers().allValues("Set-Cookie")).isEmpty();
        // 이 요청 뒤에 생긴 행만 센다 (1분 정리 작업이 다른 행을 지워도 흔들리지 않게)
        assertThat(db.queryForObject("SELECT count(*) FROM spring_session WHERE creation_time >= ?",
                Integer.class, requestedAt)).isZero();
    }

    /** 로그인 응답의 Set-Cookie 에서 "BEEZ_SESSION=값" 부분만 꺼낸다. */
    private static String sessionCookie(HttpResponse<String> login) {
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("BEEZ_SESSION=")).findFirst().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private int rowsOf(String principal) {
        return db.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Integer.class, principal);
    }

    private ConfigurableApplicationContext startAnotherBackend(Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>(Map.of(
                "server.port", "0",
                "spring.datasource.url", database.getJdbcUrl(),
                "spring.datasource.username", database.getUsername(),
                "spring.datasource.password", database.getPassword()));
        props.putAll(extra);
        // properties() 는 기본값이라 application.yml 에 밀린다. 명령행 인자로 넘겨야 yml 보다 앞선다
        String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BeezBackendApplication.class)
                .profiles("test")
                .run(args);
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getRequiredProperty("local.server.port"));
    }
}
