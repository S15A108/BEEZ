package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Real Debezium record and Kafka listener, with separate isolated fixture databases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicationKafkaLiveIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate db;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void cdcProperties(DynamicPropertyRegistry properties) {
        properties.add("beez.cdc.enabled", () -> "true");
        properties.add("beez.cdc.legacy-outbox-poc-enabled", () -> "true");
        properties.add("beez.cdc.bootstrap-servers", () -> "localhost:19092");
        properties.add("beez.cdc.group-id", () -> System.getenv("BEEZ_CDC_E2E_GROUP"));
        properties.add("beez.cdc.topic", () -> System.getenv("BEEZ_CDC_E2E_TOPIC"));
    }

    @Test
    void committedOutboxReachesActualListenerAndRollbackDoesNot() throws Exception {
        var project = fixtures().projectWithLeader();
        long projectId = project.projectId();
        long baseline = (UUID.randomUUID().getMostSignificantBits() & 0x0000ffffffffffffL) + 1;
        long sentinelBaseline = baseline + 1;
        db.update("INSERT INTO baseline(id,project_id,version_no,published_by) OVERRIDING SYSTEM VALUE VALUES(?,?,1,?)", baseline, projectId, project.leaderAccountId());
        db.update("INSERT INTO baseline(id,project_id,version_no,published_by) OVERRIDING SYSTEM VALUE VALUES(?,?,2,?)", sentinelBaseline, projectId, project.leaderAccountId());
        var initialProject = fixtures().projectWithLeader();
        long initialBaseline = baseline + 3;
        db.update("INSERT INTO baseline(id,project_id,version_no,published_by) OVERRIDING SYSTEM VALUE VALUES(?,?,1,?)", initialBaseline, initialProject.projectId(), initialProject.leaderAccountId());
        UUID committed = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        UUID sentinel = UUID.randomUUID();
        UUID initial = UUID.randomUUID();
        long rollbackBaseline = baseline + 2;
        // Only fixture IDs are mirrored; application DB data is never copied into POC DB.
        String sql = transaction(projectId, baseline, committed, false, false)
                + transaction(projectId, rollbackBaseline, rolledBack, true, false)
                + transaction(projectId, sentinelBaseline, sentinel, false, false)
                + transaction(initialProject.projectId(), initialBaseline, initial, false, true);
        Process process = new ProcessBuilder("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "cdc_poc", "-d", "cdc_poc", "-v", "ON_ERROR_STOP=1", "-At")
                .directory(Path.of(System.getenv("BEEZ_CDC_E2E_DIR")).toFile())
                .redirectErrorStream(true).start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < end && (count(committed) == 0 || count(sentinel) == 0 || count(initial) == 0)) Thread.sleep(200);
        assertThat(count(committed)).isEqualTo(1);
        assertThat(count(sentinel)).isEqualTo(1);
        assertThat(count(initial)).isEqualTo(1);
        for (UUID eventId : List.of(committed, sentinel, initial)) {
            assertThat(db.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, eventId)).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT count(*) FROM cdc_processing_job WHERE source_event_id=? AND state='WAITING'", Integer.class, eventId)).isEqualTo(1);
        }
        assertThat(count(rolledBack)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM cdc_processing_job WHERE source_event_id=?", Integer.class, rolledBack)).isZero();
    }

    private int count(UUID eventId) {
        return db.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?", Integer.class, eventId);
    }

    private String transaction(long project, long baseline, UUID id, boolean rollback, boolean initial) {
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("event_id", id.toString()); body.put("event_type", "DESIGN_PUBLISHED"); body.put("event_version", 1);
        body.put("occurred_at", "2026-01-01T00:00:00Z"); body.put("publication_kind", initial ? "INITIAL" : "CHANGE");
        body.put("project_id", Long.toString(project)); body.put("baseline_id", Long.toString(baseline));
        body.put("previous_baseline_id", initial ? null : Long.toString(baseline + 1)); body.put("change_proposal_id", initial ? null : "201");
        body.put("changed_items", initial ? List.of() : List.of(java.util.Map.of("design_item_id", "301", "change_type", "UPDATED", "previous_version_id", "401", "published_version_id", "402")));
        String payload = JSON.writeValueAsString(body);
        return "BEGIN; INSERT INTO cdc_demo.baselines VALUES (" + baseline + "," + project + ",'spring e2e'); "
                + "INSERT INTO cdc_demo.publication_outbox(event_id,event_type,event_version,project_id,baseline_id,occurred_at,payload) VALUES ('"
                + id + "','DESIGN_PUBLISHED',1," + project + "," + baseline + ",'2026-01-01T00:00:00Z','" + payload + "'::jsonb); "
                + (rollback ? "ROLLBACK;" : "COMMIT;");
    }
}
