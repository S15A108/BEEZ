package com.beez.backend.cdc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only preliminary check. This result never authorizes analysis or changes inbox state. */
@Service
public class PublicationSnapshotVerifier {
    public enum Outcome { INITIAL, INVALID, STALE, UNVERIFIED, READY_CANDIDATE }
    public record Result(Outcome outcome, String reason, boolean documentsVerified, boolean relationsVerified, boolean analysisExecuted) {}
    private record Versions(Long before, Long after) {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final PublicationEventParser parser;

    public PublicationSnapshotVerifier(JdbcClient jdbc, PublicationEventParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    /** Snapshot only: execution must recheck under its own consistency/locking policy. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Result verify(UUID eventId) {
        var rows = jdbc.sql("""
                SELECT event_id, project_id, baseline_id, publication_kind, payload::text AS payload
                FROM cdc_publication_inbox WHERE event_id = :id
                """).param("id", eventId).query().listOfRows();
        if (rows.isEmpty()) return result(Outcome.INVALID, "inbox_event_not_found");
        var row = rows.getFirst();
        long project = ((Number) row.get("project_id")).longValue();
        long baseline = ((Number) row.get("baseline_id")).longValue();
        boolean sourceExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM publication_outbox WHERE event_id=:id)")
                .param("id", eventId).query(Boolean.class).single();
        if (!sourceExists) return result(Outcome.UNVERIFIED, "source_event_missing");
        boolean sourceMatches = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM publication_outbox o
                    JOIN cdc_publication_inbox i ON i.event_id=o.event_id
                    WHERE i.event_id=:id AND o.event_type='DESIGN_PUBLISHED' AND o.event_version=1
                      AND o.project_id=i.project_id AND o.baseline_id=i.baseline_id AND o.payload=i.payload)
                """).param("id", eventId).query(Boolean.class).single();
        if (!sourceMatches) return result(Outcome.INVALID, "source_event_mismatch");
        PublicationEvent event;
        JsonNode payload;
        try {
            var wireRow = Map.of("event_id", eventId.toString(), "event_type", "DESIGN_PUBLISHED", "event_version", 1,
                    "project_id", project, "baseline_id", baseline, "payload", row.get("payload"));
            event = parser.parse(JSON.writeValueAsString(Map.of("op", "c", "after", wireRow))).orElseThrow();
            payload = JSON.readTree(event.payloadJson());
            if (!event.publicationKind().equals(row.get("publication_kind"))) return result(Outcome.INVALID, "inbox_kind_mismatch");
        } catch (RuntimeException ex) {
            return result(Outcome.INVALID, "invalid_event_contract");
        }
        if ("INITIAL".equals(event.publicationKind())) {
            var initial = jdbc.sql("SELECT version_no, change_proposal_id FROM baseline WHERE id=:baseline AND project_id=:project")
                    .param("baseline", baseline).param("project", project).query().listOfRows();
            if (initial.size() != 1 || ((Number) initial.getFirst().get("version_no")).intValue() != 1
                    || initial.getFirst().get("change_proposal_id") != null) {
                return result(Outcome.INVALID, "initial_baseline_mismatch");
            }
            var initialItems = jdbc.sql("""
                    SELECT design_item_id, design_item_version_id FROM baseline_item
                    WHERE baseline_id=:baseline AND project_id=:project
                    """).param("baseline", baseline).param("project", project).query().listOfRows();
            var expected = new HashMap<Long, Versions>();
            for (var item : initialItems) expected.put(((Number) item.get("design_item_id")).longValue(),
                    new Versions(null, ((Number) item.get("design_item_version_id")).longValue()));
            if (!expected.equals(claimedItems(payload))) return result(Outcome.INVALID, "published_items_mismatch");
            return result(Outcome.INITIAL, "initial_not_change_analysis");
        }
        long previous = Long.parseLong(payload.path("previous_baseline_id").asText());
        long proposal = Long.parseLong(payload.path("change_proposal_id").asText());
        var links = jdbc.sql("""
                SELECT p.current_baseline_id, b.version_no AS new_version, old.version_no AS old_version,
                       b.change_proposal_id, cp.status, cp.base_baseline_id, cp.published_baseline_id
                FROM project p
                JOIN baseline b ON b.project_id = p.id AND b.id = :baseline
                JOIN baseline old ON old.project_id = p.id AND old.id = :previous
                JOIN change_proposal cp ON cp.project_id = p.id AND cp.id = :proposal
                WHERE p.id = :project
                """).param("project", project).param("baseline", baseline).param("previous", previous)
                .param("proposal", proposal).query().listOfRows();
        if (links.size() != 1) return result(Outcome.INVALID, "publication_references_not_found");
        var link = links.getFirst();
        if (!"PUBLISHED".equals(link.get("status")) || !isId(link.get("base_baseline_id"), previous)
                || !isId(link.get("published_baseline_id"), baseline) || !isId(link.get("change_proposal_id"), proposal)
                || ((Number) link.get("new_version")).intValue() <= ((Number) link.get("old_version")).intValue()) {
            return result(Outcome.INVALID, "publication_references_mismatch");
        }
        boolean intermediate = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM baseline WHERE project_id=:project
                    AND version_no > :old_version AND version_no < :new_version)
                """).param("project", project).param("old_version", link.get("old_version"))
                .param("new_version", link.get("new_version")).query(Boolean.class).single();
        if (intermediate) return result(Outcome.UNVERIFIED, "intermediate_baseline_requires_policy");
        var actual = new HashMap<Long, Versions>();
        var differences = jdbc.sql("""
                SELECT COALESCE(prior_item.design_item_id, current_item.design_item_id) AS item_id,
                       prior_item.design_item_version_id AS before_id, current_item.design_item_version_id AS after_id
                FROM (SELECT design_item_id, design_item_version_id FROM baseline_item
                      WHERE baseline_id=:previous AND project_id=:project) prior_item
                FULL OUTER JOIN (SELECT design_item_id, design_item_version_id FROM baseline_item
                      WHERE baseline_id=:baseline AND project_id=:project) current_item
                  ON prior_item.design_item_id=current_item.design_item_id
                WHERE prior_item.design_item_version_id IS DISTINCT FROM current_item.design_item_version_id
                """).param("project", project).param("previous", previous).param("baseline", baseline).query().listOfRows();
        for (var difference : differences) {
            actual.put(((Number) difference.get("item_id")).longValue(),
                    new Versions(asLong(difference.get("before_id")), asLong(difference.get("after_id"))));
        }
        if (!actual.equals(claimedItems(payload))) return result(Outcome.INVALID, "published_items_mismatch");
        if (!isId(link.get("current_baseline_id"), baseline)) return result(Outcome.STALE, "baseline_no_longer_current");
        return result(Outcome.READY_CANDIDATE, "item_snapshot_matches_documents_and_relations_unverified");
    }

    private static Result result(Outcome outcome, String reason) { return new Result(outcome, reason, false, false, false); }
    private static boolean isId(Object value, long expected) { return value instanceof Number n && n.longValue() == expected; }
    private static Long asLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Long jsonId(JsonNode value) { return value.isNull() ? null : Long.parseLong(value.asText()); }
    private static Map<Long, Versions> claimedItems(JsonNode payload) {
        var claimed = new HashMap<Long, Versions>();
        for (JsonNode item : payload.path("changed_items")) {
            claimed.put(Long.parseLong(item.path("design_item_id").asText()),
                    new Versions(jsonId(item.path("previous_version_id")), jsonId(item.path("published_version_id"))));
        }
        return claimed;
    }
}
