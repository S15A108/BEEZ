package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Same isolated DB is both Debezium source and Spring inbox/queue. */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicationBoundaryLiveIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired PublicationBootstrapService bootstrap;
    @Autowired PlatformTransactionManager transactions;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:15443/boundary");
        p.add("spring.datasource.username", () -> "boundary");
        p.add("spring.datasource.password", () -> "boundary_local_only");
        p.add("beez.cdc.enabled", () -> "true");
        p.add("beez.cdc.legacy-outbox-poc-enabled", () -> "true");
        p.add("beez.cdc.bootstrap-servers", () -> "127.0.0.1:19094");
        p.add("beez.cdc.group-id", () -> "boundary-test");
        p.add("beez.cdc.topic", () -> "boundary.public.publication_outbox");
    }

    @Test void scanCandidateAndLaterRealPublicationBothReachTheQueue() throws Exception {
        long actor = db.queryForObject("INSERT INTO account(email,password_hash,display_name) VALUES(?,?,?) RETURNING id", Long.class, UUID.randomUUID()+"@example.com", "fixture", "fixture");
        long project = db.queryForObject("INSERT INTO project(name,owner_account_id) VALUES(?,?) RETURNING id", Long.class, "boundary", actor);
        long first = db.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", Long.class, project, actor);
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", first, project);
        // No fabricated historical outbox event for the pre-existing published baseline.
        assertThat(count("SELECT count(*) FROM publication_outbox WHERE baseline_id=?", first)).isZero();

        var config = Map.ofEntries(Map.entry("connector.class", "io.debezium.connector.postgresql.PostgresConnector"),
                Map.entry("database.hostname", "postgres"), Map.entry("database.port", "5432"),
                Map.entry("database.user", "boundary"), Map.entry("database.password", "boundary_local_only"),
                Map.entry("database.dbname", "boundary"), Map.entry("topic.prefix", "boundary"),
                Map.entry("plugin.name", "pgoutput"), Map.entry("slot.name", "boundary_test"),
                Map.entry("publication.name", "boundary_test"), Map.entry("publication.autocreate.mode", "filtered"),
                Map.entry("table.include.list", "public.publication_outbox"), Map.entry("snapshot.mode", "no_data"),
                Map.entry("key.converter", "org.apache.kafka.connect.json.JsonConverter"), Map.entry("key.converter.schemas.enable", "false"),
                Map.entry("value.converter", "org.apache.kafka.connect.json.JsonConverter"), Map.entry("value.converter.schemas.enable", "false"));
        var http = HttpClient.newHttpClient();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18085/connectors/boundary/config"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(config))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && count("SELECT count(*) FROM pg_replication_slots WHERE slot_name='boundary_test' AND active AND confirmed_flush_lsn IS NOT NULL") == 0) Thread.sleep(250);
        assertThat(count("SELECT count(*) FROM pg_replication_slots WHERE slot_name='boundary_test' AND active AND confirmed_flush_lsn IS NOT NULL")).isEqualTo(1);

        var scanned = bootstrap.candidatesAfter(project - 1, 1).getFirst();
        assertThat(scanned.baselineId()).isEqualTo(first);
        UUID event = UUID.randomUUID();
        long second = new TransactionTemplate(transactions).execute(status -> {
            db.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", Long.class, project);
            long next = db.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, project, actor);
            db.update("UPDATE project SET current_baseline_id=? WHERE id=?", next, project);
            var body = new java.util.LinkedHashMap<String,Object>();
            body.put("event_id",event.toString()); body.put("event_type","DESIGN_PUBLISHED"); body.put("event_version",1);
            body.put("occurred_at","2026-01-01T00:00:00Z"); body.put("publication_kind","CHANGE");
            body.put("project_id",Long.toString(project)); body.put("baseline_id",Long.toString(next));
            body.put("previous_baseline_id",Long.toString(first)); body.put("change_proposal_id","201");
            body.put("changed_items",List.of(Map.of("design_item_id","301","change_type","UPDATED","previous_version_id","401","published_version_id","402")));
            db.update("INSERT INTO publication_outbox(event_id,event_type,event_version,project_id,baseline_id,occurred_at,payload) VALUES(?,'DESIGN_PUBLISHED',1,?,?,now(),?::jsonb)", event, project, next, JSON.writeValueAsString(body));
            return next;
        });
        // Wait for the *actual* Debezium -> Kafka -> Spring inbox before initialization.
        deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && count("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?",event) == 0) Thread.sleep(250);
        assertThat(count("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?",event)).isEqualTo(1);
        assertThat(bootstrap.initializeScannedCandidate(scanned)).isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        assertThat(bootstrap.initializeScannedCandidate(scanned)).isEqualTo(PublicationBootstrapService.Initialization.ALREADY_INITIALIZED);
        assertThat(count("SELECT count(*) FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",project,first)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",project,second)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cdc_processing_job WHERE project_id=? AND state='WAITING'",project)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?",Long.class,project)).isEqualTo(1);
    }

    int count(String sql, Object... args) { return db.queryForObject(sql,Integer.class,args); }
}
