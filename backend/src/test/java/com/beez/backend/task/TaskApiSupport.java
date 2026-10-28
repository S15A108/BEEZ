package com.beez.backend.task;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * 작업 통합 테스트 공통 도우미. 실제 포트 + 세션 로그인(-170)으로 호출한다.
 * HTTP 메서드의 accountId 는 "그 계정으로 로그인한 세션"이다 — 계정마다 한 번 로그인해 세션을 재사용한다. null 이면 비로그인.
 * 에이전트는 MCP 전용이라 REST 로 부를 수 없다(401). 에이전트 규칙은 서비스를 직접 불러 확인한다.
 */
public class TaskApiSupport {

    /** 픽스처 계정은 모두 {@link AccessFixtures#PASSWORD} 로 로그인한다. bcrypt 는 느려서 한 번만 만든다 */
    private static final String PASSWORD_HASH =
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(AccessFixtures.PASSWORD);

    private final Supplier<ApiClient> newClient;
    private final Map<Long, ApiClient> sessions = new ConcurrentHashMap<>();   // 동시 START 테스트가 여러 스레드에서 부른다
    private final DataSource dataSource;
    public final JdbcClient jdbc;

    public TaskApiSupport(Supplier<ApiClient> newClient, DataSource dataSource) {
        this.newClient = newClient;
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    // ---- 데이터 준비 ----

    public void resetAndLoadFixture() {
        runSql("fixtures/project-reset.sql");
        runSql("fixtures/project-basic.sql");
        jdbc.sql("update account set password_hash = :h").param("h", PASSWORD_HASH).update();
        sessions.clear();
    }

    public void runSql(String classpath) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String sql = new String(new ClassPathResource(classpath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            st.execute(sql);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("fixture 실행 실패: " + classpath, e);
        }
    }

    public Long accountId(String email) {
        return jdbc.sql("select id from account where email = :e").param("e", email).query(Long.class).single();
    }

    public Long projectId() {
        return jdbc.sql("select id from project where name = 'P'").query(Long.class).single();
    }

    public Long memberId(String email) {
        return jdbc.sql("""
                select pm.id from project_member pm join account a on a.id = pm.account_id where a.email = :e
                """).param("e", email).query(Long.class).single();
    }

    public Long designItemId(String key) {
        return jdbc.sql("select id from design_item where key = :k").param("k", key).query(Long.class).single();
    }

    public Long currentVersionId(String key) {
        return jdbc.sql("""
                select bi.design_item_version_id
                  from baseline_item bi
                  join design_item di on di.id = bi.design_item_id
                  join project p on p.current_baseline_id = bi.baseline_id
                 where di.key = :k
                """).param("k", key).query(Long.class).single();
    }

    /** member 소유 토큰 */
    public Long agentTokenId() {
        return jdbc.sql("select id from agent_token where name = 'member-laptop'").query(Long.class).single();
    }

    /** leader 소유 토큰 */
    public Long leaderAgentTokenId() {
        return jdbc.sql("select id from agent_token where name = 'leader-laptop'").query(Long.class).single();
    }

    // ---- HTTP ----

    public HttpResponse<String> post(String path, String json, Long accountId) {
        return as(accountId).post(path, json);
    }

    public HttpResponse<String> patch(String path, String json, Long accountId) {
        return as(accountId).patch(path, json);
    }

    public HttpResponse<String> get(String path, Long accountId) {
        return as(accountId).get(path);
    }

    /** 이 계정으로 로그인한 세션. 테스트 안에서 탈퇴·제외시킨 뒤에도 같은 세션으로 다음 요청을 보낼 수 있다 */
    private ApiClient as(Long accountId) {
        if (accountId == null) {
            return newClient.get();
        }
        return sessions.computeIfAbsent(accountId, id -> newClient.get().login(
                jdbc.sql("select email from account where id = :a").param("a", id).query(String.class).single(),
                AccessFixtures.PASSWORD));
    }

    // ---- 요청 본문 ----

    /** 가장 단순한 계획: API-01 을 READS. 선행 없음 */
    public String planJson(String title, String refsJson, String dependencyIdsJson) {
        return """
                {
                  "title": "%s",
                  "goal": "목표",
                  "included_scope": ["a"],
                  "excluded_scope": [],
                  "approach": "방법",
                  "completion_criteria": ["테스트 통과"],
                  "dependency_task_ids": %s,
                  "design_refs": %s,
                  "unlinked": [],
                  "no_contract_change": true
                }
                """.formatted(title, dependencyIdsJson, refsJson);
    }

    /** 항목을 CHANGES 로 참조하는 계획 (변경 후 내용 포함) */
    public String changesRef(String key) {
        return """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "CHANGES",
                  "proposed_content": {"method":"GET","path":"/x","response":{"memberId":"long"}}}]
                """.formatted(designItemId(key), currentVersionId(key));
    }

    public String readsRef(String key) {
        return """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "READS"}]
                """.formatted(designItemId(key), currentVersionId(key));
    }

    /** 응답 JSON 에서 "field": 123 꼴의 숫자 값을 꺼낸다. 라이브러리 없이 테스트용으로만 */
    public static long longField(String json, String field) {
        String marker = "\"" + field + "\":";
        int i = json.indexOf(marker);
        if (i < 0) {
            throw new AssertionError(field + " 없음: " + json);
        }
        int start = i + marker.length();
        // ID 는 문자열("5"), 번호는 숫자(5) — 둘 다 읽는다
        if (start < json.length() && json.charAt(start) == '"') {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    public static String stringField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) {
            throw new AssertionError(field + " 없음: " + json);
        }
        int start = i + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
