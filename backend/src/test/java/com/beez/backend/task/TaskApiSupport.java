package com.beez.backend.task;

import com.beez.backend.common.auth.HeaderCallerResolver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 통합 테스트 공통 도우미. 기존 BeezBackendApplicationTests 와 같은 방식(실제 포트 + JDK HttpClient)을 쓴다.
 * 호출자는 임시 헤더(HeaderCallerResolver, test 프로필)로 지정한다.
 */
public class TaskApiSupport {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final DataSource dataSource;
    public final JdbcClient jdbc;

    public TaskApiSupport(int port, DataSource dataSource) {
        this.baseUrl = "http://localhost:" + port;
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    // ---- 데이터 준비 ----

    public void resetAndLoadFixture() {
        runSql("fixtures/project-reset.sql");
        runSql("fixtures/project-basic.sql");
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

    public HttpResponse<String> post(String path, String json, Long accountId) throws Exception {
        return send(builder(path, accountId, null).POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> postAsAgent(String path, String json, Long accountId, Long tokenId) throws Exception {
        return send(builder(path, accountId, tokenId).POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> patch(String path, String json, Long accountId) throws Exception {
        return send(builder(path, accountId, null).method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> patchAsAgent(String path, String json, Long accountId, Long tokenId) throws Exception {
        return send(builder(path, accountId, tokenId).method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> getAsAgent(String path, Long accountId, Long tokenId) throws Exception {
        return send(builder(path, accountId, tokenId).GET());
    }

    public HttpResponse<String> get(String path, Long accountId) throws Exception {
        return send(builder(path, accountId, null).GET());
    }

    private HttpRequest.Builder builder(String path, Long accountId, Long tokenId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (accountId != null) {
            b.header(HeaderCallerResolver.ACCOUNT_HEADER, String.valueOf(accountId));
        }
        if (tokenId != null) {
            b.header(HeaderCallerResolver.AGENT_TOKEN_HEADER, String.valueOf(tokenId));
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
