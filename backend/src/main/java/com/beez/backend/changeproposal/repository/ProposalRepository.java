package com.beez.backend.changeproposal.repository;

import static com.beez.backend.designitem.support.DesignJson.*;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.auth.CallerContext;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL and row mapping only. Commands lock project -> membership -> token -> proposal. */
@Repository
public class ProposalRepository {
    private final JdbcClient jdbc;
    public ProposalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void lockCaller(long project, CallerContext caller) {
        var member = jdbc.sql("""
                SELECT m.id FROM project_member m JOIN account a ON a.id=m.account_id
                WHERE m.project_id=:project AND m.account_id=:account AND m.active AND a.deleted_at IS NULL
                FOR SHARE OF m,a
                """).param("project", project).param("account", caller.accountId()).query(Long.class).optional();
        if (member.isEmpty()) throw AccessErrors.notFound("proposal_membership_changed");
        if (!caller.isHuman()) {
            boolean valid = jdbc.sql("""
                    SELECT id FROM agent_token WHERE id=:token AND project_id=:project AND account_id=:account
                      AND revoked_at IS NULL FOR SHARE
                    """).param("token", caller.agentTokenId()).param("project", project).param("account", caller.accountId())
                    .query(Long.class).optional().isPresent();
            if (!valid) throw AccessErrors.tokenRevoked("proposal_token_changed");
        }
    }

    public Map<String, Object> proposal(long project, long proposal) {
        return one("""
                SELECT c.*,p.current_baseline_id,r.snapshot_hash,r.snapshot::text AS snapshot,r.submitted_by,r.submitted_token_id,
                       a.reason AS rejection_reason
                FROM change_proposal c JOIN project p ON p.id=c.project_id
                JOIN change_proposal_revision r ON r.change_proposal_id=c.id AND r.revision_no=c.revision_no
                LEFT JOIN approval a ON a.change_proposal_id=c.id AND a.proposal_revision_no=c.revision_no AND a.decision='REJECTED'
                WHERE c.id=:proposal AND c.project_id=:project
                """, Map.of("project", project, "proposal", proposal));
    }

    public Map<String, Object> revision(long project, long proposal, int revision) {
        return one("""
                SELECT r.*,a.decision,a.reason AS rejection_reason,a.result_baseline_id FROM change_proposal_revision r
                LEFT JOIN approval a ON a.change_proposal_id=r.change_proposal_id AND a.proposal_revision_no=r.revision_no
                WHERE r.project_id=:project AND r.change_proposal_id=:proposal AND r.revision_no=:revision
                """,
                Map.of("project", project, "proposal", proposal, "revision", revision));
    }

    public List<Revision> revisions(long project, long proposal, int before, int limit) {
        return jdbc.sql("""
                SELECT r.*,a.decision,a.reason,a.decided_by,a.decided_at
                FROM change_proposal_revision r LEFT JOIN approval a
                  ON a.change_proposal_id=r.change_proposal_id AND a.proposal_revision_no=r.revision_no
                WHERE r.project_id=:project AND r.change_proposal_id=:proposal AND r.revision_no<:before
                ORDER BY r.revision_no DESC LIMIT :limit
                """).param("project", project).param("proposal", proposal).param("before", before).param("limit", limit)
                .query((rs, n) -> new Revision(rs.getInt("revision_no"), rs.getString("snapshot_hash"),
                        rs.getString("submitted_by"), rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getString("decision"), rs.getString("reason"), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant().toString())).list();
    }

    public List<Summary> list(long project, String status, String group, long before, int limit) {
        String filter = status == null ? "" : " AND status=:status";
        if ("IN_PROGRESS".equals(group)) filter += " AND status IN ('PROPOSED','IN_REVIEW','APPROVED')";
        else if (group != null) filter += " AND status=:group";
        var statement = jdbc.sql("SELECT id,key,title,status,revision_no,base_baseline_id,proposer_account_id,updated_at FROM change_proposal WHERE project_id=:project AND id<:before" + filter + " ORDER BY id DESC LIMIT :limit")
                .param("project", project).param("before", before).param("limit", limit);
        if (status != null) statement = statement.param("status", status);
        if (group != null && !"IN_PROGRESS".equals(group)) statement = statement.param("group", group);
        return statement.query((rs, n) -> new Summary(rs.getString("id"), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), rs.getInt("revision_no"), rs.getString("base_baseline_id"),
                rs.getString("proposer_account_id"), rs.getTimestamp("updated_at").toInstant().toString())).list();
    }

    public void requireRelated(long project, Long task, Long validation) {
        if (task != null) one("SELECT id FROM task WHERE id=:id AND project_id=:project", Map.of("id", task, "project", project));
        if (validation != null) {
            var row = one("SELECT v.task_id FROM validation v JOIN task t ON t.id=v.task_id WHERE v.id=:id AND t.project_id=:project",
                    Map.of("id", validation, "project", project));
            if (task != null && id(row, "task_id") != task) throw invalid("검증이 지정한 작업에 속하지 않습니다.");
        }
    }

    public Map<String, Object> baseItem(long project, long baseline, long item) {
        return one("""
                SELECT v.*,i.kind FROM baseline_item b JOIN design_item i ON i.id=b.design_item_id AND i.project_id=b.project_id
                JOIN design_item_version v ON v.id=b.design_item_version_id AND v.design_item_id=i.id
                WHERE b.project_id=:project AND b.baseline_id=:baseline AND b.design_item_id=:item
                """, Map.of("project", project, "baseline", baseline, "item", item));
    }

    public Map<Long, Map<String, Object>> baseItems(long project, long baseline, Set<Long> items) {
        if (items.isEmpty()) return Map.of();
        var rows = jdbc.sql("""
                SELECT v.*,i.kind FROM baseline_item b JOIN design_item i ON i.id=b.design_item_id AND i.project_id=b.project_id
                JOIN design_item_version v ON v.id=b.design_item_version_id AND v.design_item_id=i.id
                WHERE b.project_id=:project AND b.baseline_id=:baseline AND b.design_item_id IN (:items)
                """).param("project", project).param("baseline", baseline).param("items", items).query().listOfRows();
        var result = new HashMap<Long, Map<String, Object>>();
        for (var row : rows) result.put(id(row, "design_item_id"), row);
        return result;
    }

    public Map<String, Object> baseDocument(long project, long baseline, long document) {
        return one("""
                SELECT v.* FROM baseline_document b JOIN document_version v ON v.id=b.document_version_id AND v.document_id=b.document_id
                WHERE b.project_id=:project AND b.baseline_id=:baseline AND b.document_id=:document
                """, Map.of("project", project, "baseline", baseline, "document", document));
    }

    public Map<String, Object> draft(long project, long document, long version) {
        return one("""
                SELECT v.* FROM document_version v JOIN document d ON d.id=v.document_id
                WHERE d.project_id=:project AND d.id=:document AND v.id=:version FOR UPDATE OF v
                """, Map.of("project", project, "document", document, "version", version));
    }
    /** Extension (md/txt) and text of a document version in this project; names and compares a proposed text. */
    public record VersionText(String extension, String text) { }

    public Optional<VersionText> versionText(long project, long document, long version) {
        return jdbc.sql("""
                SELECT v.storage_key,v.content_text FROM document_version v JOIN document d ON d.id=v.document_id
                WHERE d.project_id=:project AND d.id=:document AND v.id=:version
                """).param("project", project).param("document", document).param("version", version)
                .query((rs, row) -> new VersionText(rs.getString("storage_key").endsWith(".md") ? "md" : "txt",
                        rs.getString("content_text"))).optional();
    }

    /**
     * Registers the proposed text as a DRAFT version of the document (same shape as a DOC-A12 upload) and queues its
     * indexing. Callers hold the project lock, so version numbers cannot collide.
     */
    public long createDraftVersion(long document, long parent, String storageKey, String contentHash, String text,
            String textHash, long creator) {
        long version = jdbc.sql("""
                INSERT INTO document_version(document_id,version_no,status,parse_status,storage_key,content_hash,content_text,
                  created_by,parent_version_id)
                SELECT :document,coalesce(max(version_no),0)+1,'DRAFT','PENDING',:key,:hash,:content,:creator,:parent
                FROM document_version WHERE document_id=:document RETURNING id
                """).param("document", document).param("key", storageKey).param("hash", contentHash)
                .param("content", text).param("creator", creator).param("parent", parent).query(Long.class).single();
        jdbc.sql("INSERT INTO document_processing(document_version_id,status,text_hash) VALUES(:version,'PENDING',:hash)")
                .param("version", version).param("hash", textHash).update();
        return version;
    }

    public long create(long project, CallerContext caller, Input input) {
        return jdbc.sql("""
                WITH allocated AS (SELECT nextval(pg_get_serial_sequence('change_proposal','id')) AS id)
                INSERT INTO change_proposal(id,project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,
                  proposer_token_id,title,reason,task_id,validation_id,previous_proposal_id)
                OVERRIDING SYSTEM VALUE
                SELECT id,:project,'CP-'||id,'PROPOSED',:baseline,:kind,:account,:token,:title,:reason,:task,:validation,:previous FROM allocated
                RETURNING id
                """).param("project", project).param("baseline", Long.parseLong(input.baseBaselineId()))
                .param("kind", caller.kind().name()).param("account", caller.accountId()).param("token", caller.agentTokenId())
                .param("title", input.title()).param("reason", input.reason())
                .param("task", number(input.taskId())).param("validation", number(input.validationId()))
                .param("previous", number(input.previousProposalId())).query(Long.class).single();
    }

    public void withdraw(long project, long proposal, long account) {
        jdbc.sql("""
                UPDATE change_proposal SET status='WITHDRAWN',withdrawn_by=:account,withdrawn_at=clock_timestamp(),
                  lock_version=lock_version+1,updated_at=clock_timestamp() WHERE id=:proposal AND project_id=:project
                """).param("project", project).param("proposal", proposal).param("account", account).update();
    }

    public void startReview(long project, long proposal) {
        jdbc.sql("""
                UPDATE change_proposal SET status='IN_REVIEW',lock_version=lock_version+1,updated_at=clock_timestamp()
                WHERE id=:proposal AND project_id=:project
                """).param("project", project).param("proposal", proposal).update();
    }

    /** The REJECTED approval row is what CHG-A03/A04 read as the rejection reason, so it commits with the status. */
    public void reject(long project, long proposal, int revision, long baseline, long account, String reason) {
        jdbc.sql("""
                INSERT INTO approval(change_proposal_id,proposal_revision_no,decided_by,decision,reason,base_baseline_id)
                VALUES(:proposal,:revision,:account,'REJECTED',:reason,:baseline)
                """).param("proposal", proposal).param("revision", revision).param("account", account)
                .param("reason", reason).param("baseline", baseline).update();
        jdbc.sql("""
                UPDATE change_proposal SET status='REJECTED',lock_version=lock_version+1,updated_at=clock_timestamp()
                WHERE id=:proposal AND project_id=:project
                """).param("project", project).param("proposal", proposal).update();
    }

    /** The approval behind the published baseline of a PUBLISHED proposal; empty for anything else. */
    public Optional<ApprovalRecord> publishedApproval(long project, long proposal) {
        return jdbc.sql("""
                SELECT a.id,a.change_proposal_id,a.decided_by,a.decided_at,a.result_baseline_id
                FROM approval a JOIN change_proposal c ON c.id=a.change_proposal_id AND a.proposal_revision_no=c.revision_no
                WHERE c.id=:proposal AND c.project_id=:project AND c.status='PUBLISHED' AND a.decision='APPROVED'
                  AND a.result_baseline_id IS NOT NULL
                """).param("project", project).param("proposal", proposal)
                .query((rs, n) -> new ApprovalRecord(rs.getString("id"), rs.getString("change_proposal_id"),
                        rs.getString("decided_by"), rs.getTimestamp("decided_at").toInstant().toString(),
                        rs.getString("result_baseline_id"))).optional();
    }

    public void advance(long proposal, Input input) {
        jdbc.sql("""
                UPDATE change_proposal SET title=:title,reason=:reason,base_baseline_id=:baseline,task_id=:task,
                  validation_id=:validation,status='PROPOSED',revision_no=revision_no+1,lock_version=lock_version+1,updated_at=clock_timestamp()
                WHERE id=:proposal
                """).param("proposal", proposal).param("title", input.title()).param("reason", input.reason())
                .param("baseline", Long.parseLong(input.baseBaselineId())).param("task", number(input.taskId()))
                .param("validation", number(input.validationId())).update();
    }

    public void saveRevision(long project, long proposal, int revision, CallerContext caller, Snapshot snapshot, String hash) {
        jdbc.sql("""
                INSERT INTO change_proposal_revision(change_proposal_id,project_id,revision_no,base_baseline_id,snapshot,snapshot_hash,
                  submitted_by,submitted_token_id) VALUES(:proposal,:project,:revision,:baseline,cast(:snapshot AS jsonb),:hash,:account,:token)
                """).param("proposal", proposal).param("project", project).param("revision", revision)
                .param("baseline", Long.parseLong(snapshot.input().baseBaselineId())).param("snapshot", canonical(snapshot))
                .param("hash", hash).param("account", caller.accountId()).param("token", caller.agentTokenId()).update();
        // Existing readers (#188) use the current projection; historical content lives in the immutable revision.
        jdbc.sql("DELETE FROM change_proposal_item WHERE change_proposal_id=:proposal").param("proposal", proposal).update();
        for (Item item : snapshot.input().items()) {
            jdbc.sql("""
                    INSERT INTO change_proposal_item(change_proposal_id,op,design_item_id,before_version_id,new_kind,schema_version,proposed_content)
                    VALUES(:proposal,:op,:item,:version,:kind,:schema,cast(:content AS jsonb))
                    """).param("proposal", proposal).param("op", item.operation()).param("item", number(item.designItemId()))
                    .param("version", number(item.baseDesignItemVersionId())).param("kind", item.kind())
                    .param("schema", item.schemaVersion()).param("content", item.proposedContent() == null ? null : canonical(item.proposedContent())).update();
        }
        for (ResolvedDocument doc : snapshot.documents()) {
            jdbc.sql("""
                    INSERT INTO change_proposal_document(change_proposal_id,revision_no,project_id,document_id,base_document_version_id,proposed_document_version_id)
                    VALUES(:proposal,:revision,:project,:document,:base,:proposed)
                    """).param("proposal", proposal).param("revision", revision).param("project", project)
                    .param("document", number(doc.documentId())).param("base", number(doc.baseDocumentVersionId()))
                    .param("proposed", number(doc.proposedDocumentVersionId())).update();
            if (doc.proposedDocumentVersionId() != null) {
                jdbc.sql("UPDATE document_version SET change_proposal_id=:proposal WHERE id=:version AND change_proposal_id IS NULL")
                        .param("proposal", proposal).param("version", number(doc.proposedDocumentVersionId())).update();
            }
        }
    }

    public List<Candidate> candidates(long project, long baseline, Set<Long> changedItems, Set<Long> changedDocuments, int limit) {
        // Only direct references. Version-bound graph/rule/AI candidates belong to #188/#197.
        return jdbc.sql("""
                WITH affected AS (
                  SELECT b.design_item_id FROM baseline_item b JOIN design_item_version v ON v.id=b.design_item_version_id
                  LEFT JOIN document_version d ON d.id=v.source_document_version_id
                  WHERE b.project_id=:project AND b.baseline_id=:baseline
                    AND (b.design_item_id IN (:items) OR d.document_id IN (:documents))
                ), candidates AS (
                  SELECT 'DESIGN_ITEM'::text AS type,a.design_item_id AS id,a.design_item_id AS via,NULL::bigint AS assignee FROM affected a
                  UNION
                  SELECT 'TASK',t.id,r.design_item_id,t.assignee_member_id FROM task t
                  JOIN task_plan_design_ref r ON r.plan_revision_id=t.current_plan_revision_id
                  WHERE t.project_id=:project AND t.status NOT IN ('COMPLETED','CANCELLED')
                    AND r.design_item_id IN (SELECT design_item_id FROM affected)
                  UNION
                  SELECT 'VALIDATION',v.id,r.design_item_id,t.assignee_member_id FROM validation_design_ref r
                  JOIN validation v ON v.id=r.validation_id
                  JOIN task t ON t.id=v.task_id AND v.plan_revision_id=t.current_plan_revision_id
                  WHERE t.project_id=:project AND t.status NOT IN ('COMPLETED','CANCELLED')
                    AND r.design_item_id IN (SELECT design_item_id FROM affected)
                  UNION
                  SELECT 'DOCUMENT',d.document_id,b.design_item_id,NULL::bigint FROM baseline_item b
                  JOIN design_item_version v ON v.id=b.design_item_version_id
                  JOIN document_version d ON d.id=v.source_document_version_id
                  JOIN baseline_document bd ON bd.baseline_id=b.baseline_id AND bd.document_version_id=d.id
                  WHERE b.project_id=:project AND b.baseline_id=:baseline AND b.design_item_id IN (SELECT design_item_id FROM affected)
                ) SELECT * FROM candidates ORDER BY type,id,via LIMIT :limit
                """).param("project", project).param("baseline", baseline)
                .param("items", changedItems.isEmpty() ? Set.of(-1L) : changedItems)
                .param("documents", changedDocuments.isEmpty() ? Set.of(-1L) : changedDocuments).param("limit", limit)
                .query((rs, n) -> new Candidate(rs.getString("type"), rs.getString("id"), rs.getString("via"),
                        rs.getString("assignee"), "EXPLICIT")).list();
    }

    private Map<String, Object> one(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> AccessErrors.notFound("proposal_reference_missing"));
    }
    private static Long number(String value) { return value == null ? null : Long.valueOf(value); }
}
