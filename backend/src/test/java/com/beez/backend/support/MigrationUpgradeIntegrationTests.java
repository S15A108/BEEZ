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
}
