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

    // ---- 설계 발행 흉내 (발행 -194 전까지 SQL 로) ----

    /** 항목의 새 APPROVED 버전 번호 */
    public Long newVersion(String key, int versionNo) {
        Long item = designItemId(key);
        jdbc.sql("""
                        insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                        values (:i, :n, 'APPROVED', :t, 1, '{}', :h, 'HUMAN', now())
                        """).param("i", item).param("n", versionNo).param("t", key + " v" + versionNo)
                .param("h", "h-" + key + "-v" + versionNo).update();
        return jdbc.sql("select id from design_item_version where design_item_id = :i and version_no = :n")
                .param("i", item).param("n", versionNo).query(Long.class).single();
    }

    /**
     * 기준 설계 versionNo 발행 (프로젝트 P). items 의 (항목 키 → 버전) 만 들어간다. baseline 은 UPDATE 금지라
     * 실제 순서대로: 제안(APPROVED) → baseline(제안 번호 포함) → 제안 PUBLISHED → 현재 기준 교체.
     */
    public Long publish(int versionNo, java.util.Map<String, Long> items) {
        Long projectId = projectId();
        Long leader = accountId("leader@test");
        Long base = jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        jdbc.sql("""
                        insert into change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                        values (:p, :k, 'APPROVED', :base, 'HUMAN', :a, '테스트')
                        """).param("p", projectId).param("k", "CP-" + versionNo).param("base", base).param("a", leader).update();
        Long proposal = jdbc.sql("select id from change_proposal where key = :k and project_id = :p")
                .param("k", "CP-" + versionNo).param("p", projectId).query(Long.class).single();
        jdbc.sql("insert into baseline (project_id, version_no, published_by, change_proposal_id) values (:p, :v, :a, :cp)")
                .param("p", projectId).param("v", versionNo).param("a", leader).param("cp", proposal).update();
        Long baselineId = jdbc.sql("select id from baseline where project_id = :p and version_no = :v")
                .param("p", projectId).param("v", versionNo).query(Long.class).single();
        for (java.util.Map.Entry<String, Long> e : items.entrySet()) {
            jdbc.sql("""
                            insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id)
                            values (:b, :p, :i, :ver)
                            """).param("b", baselineId).param("p", projectId)
                    .param("i", designItemId(e.getKey())).param("ver", e.getValue()).update();
        }
        jdbc.sql("update change_proposal set status = 'PUBLISHED', published_baseline_id = :b where id = :cp")
                .param("b", baselineId).param("cp", proposal).update();
        jdbc.sql("update project set current_baseline_id = :b where id = :p").param("b", baselineId).param("p", projectId).update();
        return baselineId;
    }

    /** 현재 계획에 대한 PASS 검증을 만들고 계획 참조를 검증 참조로 복사한다 (검증 워커 -159 전까지 SQL 로) */
    public long passValidation(long taskId) {
        Long revisionId = jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        Long baselineId = jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId())
                .query(Long.class).single();
        jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'PASS', 'AGENT', 'snap-' || :r, 'r1')
                        """).param("t", taskId).param("r", revisionId).param("b", baselineId).update();
        Long validationId = jdbc.sql("select max(id) from validation where task_id = :t").param("t", taskId)
                .query(Long.class).single();
        jdbc.sql("""
                        insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                        select :v, design_item_id, design_item_version_id, relation
                          from task_plan_design_ref where plan_revision_id = :r
                        """).param("v", validationId).param("r", revisionId).update();
        return validationId;
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

    public HttpResponse<String> postWithHeaders(String path, String json, Long accountId, String... headers) {
        return as(accountId).post(path, json, headers);
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

    public String implementsRef(String key) {
        return """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "IMPLEMENTS"}]
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
