package com.beez.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The packaged/default Flyway path must not install the legacy CDC experiment. */
class CdcMigrationIsolationIntegrationTest extends IntegrationTestSupport {
    @Test
    void defaultRuntimeLocationsDoNotCreatePocTablesOrHistory() throws Exception {
        // Dedicated disposable DB within the IntegrationTestSupport Testcontainer.
        String database = "cdc_default_" + Long.toUnsignedString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        try {
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("""
                        SELECT to_regclass('public.publication_outbox'),
                               to_regclass('public.cdc_publication_inbox'),
                               to_regclass('public.cdc_processing_cursor'),
                               to_regclass('public.cdc_processing_job')
                        """)) {
                    assertThat(rows.next()).isTrue();
                    for (int column = 1; column <= 4; column++) assertThat(rows.getString(column)).isNull();
                }
                try (var rows = statement.executeQuery("""
                        SELECT count(*) FROM flyway_schema_history WHERE script IN
                          ('V13__publication_outbox.sql', 'V14__cdc_publication_inbox.sql',
                           'V15__cdc_processing_jobs.sql', 'R__cdc_poc_fixture.sql')
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    @Test
    void repeatablePocFixtureCanBeReappliedWithoutDiscardingRows() {
        var project = fixtures().projectWithLeader();
        Long baseline = jdbc.queryForObject("""
                INSERT INTO baseline(project_id,version_no,published_by) VALUES (?,1,?) RETURNING id
                """, Long.class, project.projectId(), project.leaderAccountId());
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO publication_outbox(event_id,event_type,event_version,project_id,baseline_id,occurred_at,payload)
                VALUES (?,'DESIGN_PUBLISHED',1,?,?,now(),cast('{}' AS jsonb))
                """, eventId, project.projectId(), baseline);

        var fixture = new ClassPathResource("db/cdc-poc/R__cdc_poc_fixture.sql");
        new ResourceDatabasePopulator(fixture).execute(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM publication_outbox WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
    }

}
