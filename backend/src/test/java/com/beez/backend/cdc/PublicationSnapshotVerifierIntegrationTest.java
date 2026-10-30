package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class PublicationSnapshotVerifierIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate db;
    @Autowired PublicationEventParser parser;
    @Autowired PublicationInboxService inbox;
    @Autowired PublicationSnapshotVerifier verifier;
    private final JsonMapper json = JsonMapper.builder().build();
    long project, actor, previous, current, proposal, updateItem, addedItem, removedItem, beforeUpdate, afterUpdate, addedVersion, removedVersion;

    @BeforeEach void setup() {
        var fixture = fixtures().projectWithLeader();
        project = fixture.projectId(); actor = fixture.leaderAccountId();
        previous = scalar("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", project, actor);
        proposal = scalar("""
            INSERT INTO change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,reason)
            VALUES(?, 'CP-1', 'APPROVED', ?, 'HUMAN', ?, 'test') RETURNING id
            """, project, previous, actor);
        current = scalar("INSERT INTO baseline(project_id,version_no,published_by,change_proposal_id) VALUES(?,2,?,?) RETURNING id", project, actor, proposal);
        db.update("UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=? WHERE id=?", current, proposal);
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", current, project);
        updateItem = item("API-1"); addedItem = item("API-2"); removedItem = item("API-3");
        beforeUpdate = version(updateItem, 1); afterUpdate = version(updateItem, 2);
        addedVersion = version(addedItem, 1); removedVersion = version(removedItem, 1);
        ref(previous, updateItem, beforeUpdate); ref(current, updateItem, afterUpdate);
        ref(current, addedItem, addedVersion); ref(previous, removedItem, removedVersion);
    }

    private long scalar(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private long item(String key) {
        return scalar("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,'API_CONTRACT') RETURNING id", project, key);
    }
    private long version(long item, int number) {
        return scalar("""
            INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin)
            VALUES(?,?,'APPROVED','test',1,'{}'::jsonb,'sha','HUMAN') RETURNING id
            """, item, number);
    }
    private void ref(long baseline, long item, long version) {
        db.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(?,?,?,?)",
                baseline, project, item, version);
    }
    private Map<String,Object> change(long item, Long before, Long after, String type) {
        var result = new LinkedHashMap<String,Object>();
        result.put("design_item_id", Long.toString(item));
        result.put("previous_version_id", before == null ? null : before.toString());
        result.put("published_version_id", after == null ? null : after.toString());
        result.put("change_type", type);
        return result;
    }
    private List<Map<String,Object>> changes() {
        return List.of(change(updateItem, beforeUpdate, afterUpdate, "UPDATED"),
                change(addedItem, null, addedVersion, "ADDED"), change(removedItem, removedVersion, null, "REMOVED"));
    }
    private UUID accept(String kind, long old, long proposalId, List<Map<String,Object>> changes) {
        return accept(kind, old, proposalId, changes, false);
    }
    private UUID accept(String kind, long old, long proposalId, List<Map<String,Object>> changes, boolean bypassInboxForCorruptionTest) {
        UUID id = UUID.randomUUID();
        var body = new LinkedHashMap<String,Object>();
        body.put("event_id", id.toString()); body.put("event_type", "DESIGN_PUBLISHED"); body.put("event_version", 1);
        body.put("occurred_at", "2026-10-29T06:00:00Z"); body.put("publication_kind", kind);
        body.put("project_id", Long.toString(project)); body.put("baseline_id", Long.toString(current));
        body.put("previous_baseline_id", "INITIAL".equals(kind) ? null : Long.toString(old));
        body.put("change_proposal_id", "INITIAL".equals(kind) ? null : Long.toString(proposalId));
        body.put("changed_items", changes);
        // Each test publication has one source event; replace only this isolated fixture between cases.
        db.update("DELETE FROM publication_outbox WHERE project_id=? AND baseline_id=?", project, current);
        db.update("""
                INSERT INTO publication_outbox(event_id,event_type,event_version,project_id,baseline_id,occurred_at,payload)
                VALUES(?,'DESIGN_PUBLISHED',1,?,?,now(),CAST(? AS jsonb))
                """, id, project, current, json.writeValueAsString(body));
        var row = Map.of("event_id", id.toString(), "event_type", "DESIGN_PUBLISHED", "event_version", 1,
                "project_id", project, "baseline_id", current, "payload", json.writeValueAsString(body));
        if (bypassInboxForCorruptionTest) {
            db.update("""
                INSERT INTO cdc_publication_inbox(event_id,project_id,baseline_id,publication_kind,status,payload)
                VALUES(?,?,?,'INITIAL','PENDING',CAST(? AS jsonb))
                """, id, project, current, json.writeValueAsString(body));
        } else {
            inbox.accept(parser.parse(json.writeValueAsString(Map.of("op", "c", "after", row))).orElseThrow());
        }
        return id;
    }

    @Test void matchesAllThreeOperationsButDoesNotVerifyRelationsOrRunAi() {
        UUID id = accept("CHANGE", previous, proposal, changes());
        var result = verifier.verify(id);
        assertThat(result.outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.READY_CANDIDATE);
        assertThat(result.documentsVerified()).isFalse();
        assertThat(result.relationsVerified()).isFalse();
        assertThat(result.analysisExecuted()).isFalse();
        assertThat(db.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, id)).isEqualTo("PENDING");
    }

    @Test void mismatchedOrMissingChangesAreInvalid() {
        var missing = new ArrayList<>(changes()); missing.removeLast();
        assertThat(verifier.verify(accept("CHANGE", previous, proposal, missing)).reason()).isEqualTo("published_items_mismatch");
        var altered = new ArrayList<>(changes()); altered.set(0, change(updateItem, beforeUpdate, addedVersion, "UPDATED"));
        assertThat(verifier.verify(accept("CHANGE", previous, proposal, altered)).reason()).isEqualTo("published_items_mismatch");
        var extra = new ArrayList<>(changes()); extra.add(change(999999L, null, 888888L, "ADDED"));
        assertThat(verifier.verify(accept("CHANGE", previous, proposal, extra)).reason()).isEqualTo("published_items_mismatch");
    }

    @Test void nonexistentAndForeignPublicationReferencesAreInvalid() {
        var other = fixtures().projectWithLeader();
        long foreign = scalar("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", other.projectId(), other.leaderAccountId());
        assertThat(verifier.verify(accept("CHANGE", foreign, proposal, changes())).outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.INVALID);
        assertThat(verifier.verify(accept("CHANGE", previous, 999999L, changes())).outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.INVALID);
        assertThat(verifier.verify(UUID.randomUUID()).reason()).isEqualTo("inbox_event_not_found");
    }

    @Test void changedCurrentBaselineIsStaleNotSuccessfulOrDeleted() {
        UUID event = accept("CHANGE", previous, proposal, changes());
        long next = scalar("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,3,?) RETURNING id", project, actor);
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", next, project);
        assertThat(verifier.verify(event).outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.STALE);
        assertThat(db.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, event)).isEqualTo("PENDING");
    }

    @Test void initialIsNotChangeAnalysisAndBadProposalStateIsRejected() {
        long newBaseline = current;
        current = previous;
        assertThat(verifier.verify(accept("INITIAL", 0, 0, List.of(
                change(updateItem, null, beforeUpdate, "ADDED"), change(removedItem, null, removedVersion, "ADDED")))).outcome())
                .isEqualTo(PublicationSnapshotVerifier.Outcome.INITIAL);
        current = newBaseline;
        assertThat(verifier.verify(accept("INITIAL", 0, 0, List.of(), true)).reason()).isEqualTo("initial_baseline_mismatch");
        UUID event = accept("CHANGE", previous, proposal, changes());
        db.update("UPDATE change_proposal SET status='APPROVED',published_baseline_id=NULL WHERE id=?", proposal);
        assertThat(verifier.verify(event).reason()).isEqualTo("publication_references_mismatch");
    }

    @Test void absentOrDifferentSourceEventCannotBeTrusted() {
        UUID missing = accept("CHANGE", previous, proposal, changes());
        db.update("DELETE FROM publication_outbox WHERE event_id=?", missing);
        var missingResult = verifier.verify(missing);
        assertThat(missingResult.outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.UNVERIFIED);
        assertThat(missingResult.reason()).isEqualTo("source_event_missing");
        UUID changed = accept("CHANGE", previous, proposal, changes());
        db.update("UPDATE publication_outbox SET payload=jsonb_set(payload, '{event_type}', '\"OTHER\"'::jsonb) WHERE event_id=?", changed);
        var changedResult = verifier.verify(changed);
        assertThat(changedResult.outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.INVALID);
        assertThat(changedResult.reason()).isEqualTo("source_event_mismatch");
    }

    @Test void intermediateBaselineRequiresPolicy() {
        long proposal2 = scalar("""
            INSERT INTO change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,reason)
            VALUES(?, 'CP-2', 'APPROVED', ?, 'HUMAN', ?, 'test') RETURNING id
            """, project, previous, actor);
        long next = scalar("INSERT INTO baseline(project_id,version_no,published_by,change_proposal_id) VALUES(?,3,?,?) RETURNING id", project, actor, proposal2);
        db.update("UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=? WHERE id=?", next, proposal2);
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", next, project);
        current = next;
        assertThat(verifier.verify(accept("CHANGE", previous, proposal2, changes())).outcome())
                .isEqualTo(PublicationSnapshotVerifier.Outcome.UNVERIFIED);
    }

    @Test void documentChangeIsExplicitlyUnverified() {
        long document = scalar("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','test',?) RETURNING id", project, actor);
        long oldDoc = scalar("""
            INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash)
            VALUES(?,1,'DRAFT','old','old') RETURNING id
            """, document);
        long newDoc = scalar("""
            INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash)
            VALUES(?,2,'DRAFT','new','new') RETURNING id
            """, document);
        db.update("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(?,?,?,?)", previous, project, document, oldDoc);
        db.update("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(?,?,?,?)", current, project, document, newDoc);
        var result = verifier.verify(accept("CHANGE", previous, proposal, changes()));
        assertThat(result.outcome()).isEqualTo(PublicationSnapshotVerifier.Outcome.READY_CANDIDATE);
        assertThat(result.documentsVerified()).isFalse();
        assertThat(result.relationsVerified()).isFalse();
    }
}
