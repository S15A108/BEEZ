package com.beez.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 이미 데이터가 있는 DB 를 올리는 업그레이드 시험. V7 → V8 (S15P31A108-166 리뷰 반영), V8 → V9 (S15P31A108-167).
 * 같은 컨테이너 안에 빈 데이터베이스를 따로 만들어 V7 까지 적용하고, 계획 리비전을 넣은 뒤 V8 을 적용한다.
 * Flyway 는 마이그레이션 파일을 번호 순서로 적용하는 도구이고, target("7") 은 "7번까지만" 적용하라는 뜻이다.
 */
class MigrationUpgradeIntegrationTests extends IntegrationTestSupport {

    @Test
    void v8_fillsBaseBaselineOnlyFromPacket_andLeavesUnknownAsNull() throws Exception {
        String db = "upgrade_v8_" + System.nanoTime();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("create database " + db);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db);
        try {
            // V8 바로 앞까지 (이 PR 의 마이그레이션이 V8 이다)
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("7").load().migrate();

            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("insert into account (email, password_hash, display_name) values ('l@test', 'x', 'L')");
                st.execute("insert into project (name, owner_account_id) select 'U', id from account where email = 'l@test'");
                st.execute("insert into project_member (project_id, account_id, role) select p.id, a.id, 'LEADER' from project p, account a");
                st.execute("insert into baseline (project_id, version_no, published_by) select p.id, 1, a.id from project p, account a");
                st.execute("insert into baseline (project_id, version_no, published_by) select p.id, 2, a.id from project p, account a");
                // 계획이 없는 작업은 DRAFT 여야 한다 (V2 CHECK)
                st.execute("insert into task (project_id, key, title, status) select id, 'T-1', 't', 'DRAFT' from project");
                // v1 기준으로 만든 근거 패킷
                st.execute("insert into dev_context_packet (project_id, request, baseline_id, content, content_hash) "
                        + "select p.id, 'r', b.id, '{}', 'h' from project p join baseline b on b.project_id = p.id and b.version_no = 1");
                // 리비전 1: 근거 패킷 있음 → v1 / 리비전 2: 근거 패킷 없음 → 알 수 없음(NULL)
                st.execute("insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id, context_packet_id) "
                        + "select t.id, 1, 't', 'g', 'h1', a.id, k.id from task t, account a, dev_context_packet k");
                st.execute("insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id) "
                        + "select t.id, 2, 't', 'g', 'h2', a.id from task t, account a");
            }

            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("""
                        select r.revision_no, b.version_no
                          from task_plan_revision r left join baseline b on b.id = r.base_baseline_id
                         order by r.revision_no
                        """);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(2)).isEqualTo(1);           // 리비전 1 → 패킷의 기준 v1
                assertThat(rs.next()).isTrue();
                rs.getInt(2);
                assertThat(rs.wasNull()).isTrue();                // 리비전 2 → 알 수 없음
                // 채운 뒤 UPDATE 금지 트리거가 다시 켜져 있어야 한다
                ResultSet trg = st.executeQuery("select tgenabled from pg_trigger where tgname = 'trg_plan_immutable'");
                assertThat(trg.next()).isTrue();
                assertThat(trg.getString(1)).isEqualTo("O");     // O = 켜짐
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement st = admin.createStatement()) {
                st.execute("drop database if exists " + db + " with (force)");
            }
        }
    }

    /**
     * V9: 기존 의존관계 행에 상태를 채운다. 당시 기준·버전·근거를 복원할 수 없으므로 명시 관계는 확인자가 있어도 재확인 필요,
     * AI 후보는 후보. 명시 관계가 있는 프로젝트의 관계 집합 리비전은 1 에서 시작한다
     */
    @Test
    void v9_fillsDependencyStatus_fromExistingConfirmation() throws Exception {
        String db = "upgrade_v9_" + System.nanoTime();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("create database " + db);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db);
        try {
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("8").load().migrate();

            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("insert into account (email, password_hash, display_name) values ('l@test', 'x', 'L')");
                st.execute("insert into project (name, owner_account_id) select 'U', id from account where email = 'l@test'");
                for (String key : new String[] {"A", "B", "C", "D"}) {
                    st.execute("insert into design_item (project_id, key, kind) select id, '" + key + "', 'API_CONTRACT' from project");
                }
                // A→B 확인자·시각 있음 / A→C 없음 / A→D AI 후보
                st.execute("insert into design_item_dependency (project_id, from_item_id, to_item_id, origin, confirmed_by, confirmed_at) "
                        + "select p.id, a.id, b.id, 'EXPLICIT', acc.id, now() from project p, account acc, design_item a, design_item b "
                        + "where a.key = 'A' and b.key = 'B'");
                st.execute("insert into design_item_dependency (project_id, from_item_id, to_item_id, origin) "
                        + "select p.id, a.id, b.id, 'EXPLICIT' from project p, design_item a, design_item b where a.key = 'A' and b.key = 'C'");
                st.execute("insert into design_item_dependency (project_id, from_item_id, to_item_id, origin) "
                        + "select p.id, a.id, b.id, 'AI_CANDIDATE' from project p, design_item a, design_item b where a.key = 'A' and b.key = 'D'");
            }

            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("""
                        select b.key, d.status from design_item_dependency d join design_item b on b.id = d.to_item_id
                         order by b.key
                        """);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo("NEEDS_RECHECK");  // A→B 확인자는 있지만 근거 복원 불가
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo("NEEDS_RECHECK");  // A→C 확정 근거 없음
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo("CANDIDATE");      // A→D
                ResultSet set = st.executeQuery("select revision from design_relation_set");
                assertThat(set.next()).isTrue();
                assertThat(set.getLong(1)).isEqualTo(1);
                assertThat(set.next()).isFalse();
                ResultSet trg = st.executeQuery("select tgenabled from pg_trigger where tgname = 'trg_dependency_history_immutable'");
                assertThat(trg.next()).isTrue();
                assertThat(trg.getString(1)).isEqualTo("O");
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement st = admin.createStatement()) {
                st.execute("drop database if exists " + db + " with (force)");
            }
        }
    }

    /**
     * V21 (S15P31A108-169): 공통 사용량 표에 적지 않고 각 실행 기록에만 남은 과거 사용량(검증 실행·승인 전 영향 분석·발행 뒤 영향 분석 시도)을
     * 런타임 기록과 같은 규칙으로 옮긴다. 같은 호출 키는 한 번만, 틀린 값은 그 칸만 비우고, 시각은 그 실행이 끝난 시각
     */
    @Test
    void v21_backfillsUsageFromRunRecords_onceAndWithRuntimeRules() throws Exception {
        String db = "upgrade_v21_" + System.nanoTime();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("create database " + db);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db);
        try {
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("20").load().migrate();

            long project;
            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("insert into account (email, password_hash, display_name) values ('l@test', 'x', 'L')");
                st.execute("insert into project (name, owner_account_id) select 'U', id from account where email = 'l@test'");
                ResultSet p = st.executeQuery("select id from project");
                p.next();
                project = p.getLong(1);
                // 실행 기록끼리의 참조(작업·계획·제안·기준)는 이 시험의 관심이 아니라 참조 검사를 끄고 넣는다 (사용량 표의 프로젝트 참조는 실제 행)
                st.execute("set session_replication_role = replica");
                st.execute("insert into task (id, project_id, key, title, status) overriding system value values (701, " + project + ", 'T-1', 't', 'DRAFT')");
                st.execute("insert into validation (id, task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version) "
                        + "overriding system value values (501, 701, 1, 1, 'PASS', 'AGENT', 'h', 'r'), (502, 701, 1, 1, 'ERROR', 'AGENT', 'h', 'r')");
                st.execute("""
                        insert into validation_run (id, validation_id, attempt, kind, status, analyzer_version, usage, finished_at)
                        overriding system value values
                         (9001, 501, 1, 'MODEL', 'OK', 'gpt-x', '[
                            {"request_key":"v-1","tokens_known":true,"input_tokens":10,"output_tokens":"5","estimated_cost_amount":"0.0012345678","currency":"USD","latency_ms":12},
                            {"request_key":"v-2","tokens_known":true,"input_tokens":-3,"output_tokens":1e99,"estimated_cost_amount":"abc","currency":"USD","feature":"WEIRD"},
                            {"request_key":""},{"no_key":1},"text",
                            {"request_key":"v-3","estimated_cost_amount":"9999999","currency":"USD","failed":true}]', '2026-10-30T10:00:00Z'),
                         (9002, 502, 1, 'MODEL', 'ERROR', null, '[{"request_key":"v-4"},{"request_key":"v-5","model":"m2"}]', '2026-10-31T10:00:00Z')
                        """);
                st.execute("insert into change_impact_run (id, project_id, change_proposal_id, revision_no, snapshot_hash, base_baseline_id, "
                        + "relation_set_revision, input_fingerprint, status, generation, usage, usage_status, request_key, requested_by, finished_at, lease_until) "
                        + "overriding system value values (8001, " + project + ", 1, 1, 's', 1, 1, 'f', 'COMPLETED', '{\"model\":\"impact-m\"}', "
                        + "'[{\"request_key\":\"i-1\",\"feature\":\"CHANGE_IMPACT\"},{\"request_key\":\"v-1\"}]', 'REPORTED', 'rk', 1, "
                        + "'2026-11-01T01:00:00Z', now())");
                st.execute("insert into published_change_analysis (id, project_id, published_baseline_id, change_proposal_id, status) "
                        + "overriding system value values (6001, " + project + ", 1, 1, 'COMPLETED')");
                st.execute("insert into published_analysis_attempt (intake_id, attempt, outcome, usage, usage_status, finished_at) values "
                        + "(6001, 1, 'FAILED', '[{\"request_key\":\"p-1\",\"tokens_known\":true,\"input_tokens\":3,\"output_tokens\":4}]', 'REPORTED', '2026-11-02T01:00:00Z'), "
                        + "(6001, 2, 'COMPLETED', '[{\"request_key\":\"p-1\"},{\"request_key\":\"p-2\"}]', 'REPORTED', '2026-11-02T02:00:00Z')");
                st.execute("set session_replication_role = origin");
            }

            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("""
                        select request_key, feature, task_id, validation_run_id, model, tokens_known, input_tokens, output_tokens,
                               estimated_cost_amount::text as cost, currency, failed, created_at::text as at, project_id
                          from llm_usage order by request_key
                        """);
                java.util.List<String> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    assertThat(rs.getLong("project_id")).isEqualTo(project);
                    rows.add(rs.getString("request_key") + "|" + rs.getString("feature") + "|" + rs.getString("task_id") + "|"
                            + rs.getString("validation_run_id") + "|" + rs.getString("model") + "|" + rs.getBoolean("tokens_known") + "|"
                            + rs.getString("input_tokens") + "|" + rs.getString("output_tokens") + "|" + rs.getString("cost") + "|"
                            + rs.getString("currency") + "|" + rs.getBoolean("failed"));
                }
                assertThat(rows).containsExactly(
                        "i-1|CHANGE_IMPACT|null|null|impact-m|false|null|null|null|null|false",          // 모델은 실행의 generation
                        "p-1|CHANGE_IMPACT|null|null|unknown|true|3|4|null|null|false",                   // 재시도가 같은 키를 보고해도 한 번
                        "p-2|CHANGE_IMPACT|null|null|unknown|false|null|null|null|null|false",
                        "v-1|TASK_VALIDATION|701|9001|gpt-x|true|10|5|0.001235|USD|false",                // 글자 숫자·소수 6자리 반올림
                        "v-2|TASK_VALIDATION|701|9001|gpt-x|true|null|null|null|null|false",              // 틀린 토큰·비용은 그 칸만 비움, 모르는 기능은 실행 기능
                        "v-3|TASK_VALIDATION|701|9001|gpt-x|false|null|null|null|null|true",              // 범위 밖 비용은 비움
                        "v-5|TASK_VALIDATION|701|9002|m2|false|null|null|null|null|false");               // v-4 는 모델을 알 수 없어 옮기지 않음 (런타임과 같음)
                ResultSet at = st.executeQuery("select created_at = '2026-10-30T10:00:00Z'::timestamptz from llm_usage where request_key = 'v-1'");
                assertThat(at.next()).isTrue();
                assertThat(at.getBoolean(1)).isTrue();                                                // 그 실행이 끝난 시각
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement st = admin.createStatement()) {
                st.execute("drop database if exists " + db + " with (force)");
            }
        }
    }
}
