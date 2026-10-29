package com.beez.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 이미 데이터가 있는 DB 를 V7 → V8 로 올리는 업그레이드 시험 (S15P31A108-166 리뷰 반영).
 * 같은 컨테이너 안에 빈 데이터베이스를 따로 만들어 V7 까지 적용하고, 계획 리비전을 넣은 뒤 V8 을 적용한다.
 * Flyway 는 마이그레이션 파일을 번호 순서로 적용하는 도구이고, target("7") 은 "7번까지만" 적용하라는 뜻이다.
 */
class MigrationUpgradeIntegrationTests extends IntegrationTestSupport {

    @Test
    void v8_fillsBaseBaselineOfExistingPlanRevisions() throws Exception {
        String db = "upgrade_v8_" + System.nanoTime();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("create database " + db);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db);

        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("7").load().migrate();

        try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute("insert into account (email, password_hash, display_name) values ('l@test', 'x', 'L')");
            st.execute("insert into project (name, owner_account_id) select 'U', id from account where email = 'l@test'");
            st.execute("insert into project_member (project_id, account_id, role) select p.id, a.id, 'LEADER' from project p, account a");
            // v1 은 어제, v2 는 지금 발행
            st.execute("insert into baseline (project_id, version_no, published_by, published_at) "
                    + "select p.id, 1, a.id, now() - interval '1 day' from project p, account a");
            st.execute("insert into baseline (project_id, version_no, published_by, published_at) "
                    + "select p.id, 2, a.id, now() from project p, account a");
            st.execute("insert into task (project_id, key, title, status) select id, 'T-1', 't', 'READY' from project");
            // 리비전 1: v1 과 v2 사이에 작성 → 기준 v1 / 리비전 2: 첫 발행보다 먼저 작성 → 기준 없음
            st.execute("insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id, created_at) "
                    + "select t.id, 1, 't', 'g', 'h1', a.id, now() - interval '1 hour' from task t, account a");
            st.execute("insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id, created_at) "
                    + "select t.id, 2, 't', 'g', 'h2', a.id, now() - interval '2 days' from task t, account a");
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
            assertThat(rs.getInt(2)).isEqualTo(1);           // 리비전 1 → v1
            assertThat(rs.next()).isTrue();
            rs.getInt(2);
            assertThat(rs.wasNull()).isTrue();                // 리비전 2 → 기준 없음
            // 채운 뒤 UPDATE 금지 트리거가 다시 켜져 있어야 한다
            ResultSet trg = st.executeQuery("select tgenabled from pg_trigger where tgname = 'trg_plan_immutable'");
            assertThat(trg.next()).isTrue();
            assertThat(trg.getString(1)).isEqualTo("O");     // O = 켜짐
        }
    }
}
