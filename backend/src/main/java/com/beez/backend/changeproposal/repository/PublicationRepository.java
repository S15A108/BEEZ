package com.beez.backend.changeproposal.repository;

import static com.beez.backend.designitem.support.DesignJson.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.designitem.dto.BaselineDocumentRefResponse;
import com.beez.backend.designitem.dto.BaselineItemRefResponse;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublicationRepository {
    private final JdbcClient jdbc;
    public PublicationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> baseline(long project, long baseline) {
        return jdbc.sql("SELECT * FROM baseline WHERE project_id=:project AND id=:baseline")
                .param("project", project).param("baseline", baseline).query().singleRow();
    }
    public List<Map<String, Object>> items(long project, long baseline) {
        return jdbc.sql("""
                SELECT v.*,i.kind,i.retired_at,
                  (SELECT max(n.version_no)+1 FROM design_item_version n WHERE n.design_item_id=i.id) AS next_version_no
                FROM baseline_item b JOIN design_item i ON i.id=b.design_item_id
                JOIN design_item_version v ON v.id=b.design_item_version_id
                WHERE b.project_id=:project AND b.baseline_id=:baseline ORDER BY i.id
                """).param("project", project).param("baseline", baseline).query().listOfRows();
    }
    public List<Map<String, Object>> documents(long project, long baseline) {
        return jdbc.sql("""
                SELECT v.* FROM baseline_document b JOIN document_version v ON v.id=b.document_version_id
                WHERE b.project_id=:project AND b.baseline_id=:baseline ORDER BY b.document_id
                """).param("project", project).param("baseline", baseline).query().listOfRows();
    }
    public List<Map<String, Object>> proposedDocuments(long project, long proposal, int revision) {
        return jdbc.sql("""
                SELECT c.document_id,c.base_document_version_id,c.proposed_document_version_id,
                       v.status,v.parent_version_id,v.change_proposal_id,v.content_hash,v.content_text
                FROM change_proposal_document c JOIN document_version v ON v.id=c.proposed_document_version_id
                  AND v.document_id=c.document_id
                WHERE c.project_id=:project AND c.change_proposal_id=:proposal AND c.revision_no=:revision
                ORDER BY c.document_id
                """).param("project", project).param("proposal", proposal).param("revision", revision).query().listOfRows();
    }
    public long insertBaseline(long project, int version, long proposal, long actor) {
        return jdbc.sql("""
                INSERT INTO baseline(project_id,version_no,change_proposal_id,published_by)
                VALUES(:project,:version,:proposal,:actor) RETURNING id
                """).param("project", project).param("version", version).param("proposal", proposal).param("actor", actor)
                .query(Long.class).single();
    }
    public void approveDocument(long before, long after, long actor) {
        requireOne(jdbc.sql("UPDATE document_version SET status='SUPERSEDED',updated_at=now() WHERE id=:id AND status='APPROVED'")
                .param("id", before).update());
        requireOne(jdbc.sql("""
                UPDATE document_version SET status='APPROVED',approved_by=:actor,approved_at=now(),updated_at=now(),
                  lock_version=lock_version+1 WHERE id=:id AND status='DRAFT'
                """).param("actor", actor).param("id", after).update());
    }
    public long insertVersion(long item, int version, String title, int schema, Map<String, Object> content,
                              Long source, Object locator, String origin, long author) {
        return jdbc.sql("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                  source_document_version_id,source_locator,origin,created_by,approved_at)
                VALUES(:item,:version,'APPROVED',:title,:schema,cast(:content AS jsonb),:hash,:source,
                  cast(:locator AS jsonb),:origin,:author,now()) RETURNING id
                """).param("item", item).param("version", version).param("title", title).param("schema", schema)
                .param("content", canonical(content)).param("hash", hash(content)).param("source", source)
                .param("locator", locator == null ? null : locator.toString()).param("origin", origin)
                .param("author", author).query(Long.class).single();
    }
    public void supersedeItem(long version) {
        requireOne(jdbc.sql("UPDATE design_item_version SET status='SUPERSEDED',updated_at=now() WHERE id=:id AND status='APPROVED'")
                .param("id", version).update());
    }
    public void retire(long project, long item) {
        requireOne(jdbc.sql("UPDATE design_item SET retired_at=now(),updated_at=now() WHERE id=:id AND project_id=:project AND retired_at IS NULL")
                .param("id", item).param("project", project).update());
    }
    public void publish(long project, long proposal, int revision, long previous, long baseline, long actor) {
        jdbc.sql("""
                INSERT INTO approval(change_proposal_id,proposal_revision_no,decided_by,decision,base_baseline_id,result_baseline_id)
                VALUES(:proposal,:revision,:actor,'APPROVED',:previous,:baseline)
                """).param("proposal", proposal).param("revision", revision).param("actor", actor)
                .param("previous", previous).param("baseline", baseline).update();
        requireOne(jdbc.sql("""
                UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=:baseline,
                  lock_version=lock_version+1,updated_at=clock_timestamp()
                WHERE project_id=:project AND id=:proposal AND revision_no=:revision AND status IN ('PROPOSED','IN_REVIEW')
                """).param("project", project).param("proposal", proposal).param("revision", revision).param("baseline", baseline).update());
        requireOne(jdbc.sql("""
                UPDATE project SET current_baseline_id=:baseline,updated_at=now()
                WHERE id=:project AND current_baseline_id=:previous
                """).param("project", project).param("previous", previous).param("baseline", baseline).update());
    }
    public int openReviewRequests(long proposal, int revision) {
        return jdbc.sql("""
                SELECT count(*) FROM change_comment c
                WHERE c.change_proposal_id=:proposal AND c.proposal_revision_no=:revision AND c.kind='REVIEW_REQUEST'
                  AND EXISTS (SELECT 1 FROM change_comment_target t WHERE t.change_comment_id=c.id
                    AND NOT EXISTS (SELECT 1 FROM change_comment reply WHERE reply.change_proposal_id=c.change_proposal_id
                      AND reply.proposal_revision_no=c.proposal_revision_no AND reply.author_account_id=t.account_id
                      AND reply.kind IN ('COMMENT','REVIEW_REQUEST') AND reply.id>c.id))
                """).param("proposal", proposal).param("revision", revision).query(Integer.class).single();
    }
    public Baseline response(long project, long baseline) {
        var row=baseline(project, baseline);
        return new Baseline(Long.toString(baseline), Long.toString(project), ((Number)row.get("version_no")).intValue(),
                documents(project, baseline).stream().map(d -> new BaselineDocumentRefResponse(d.get("document_id").toString(),d.get("id").toString())).toList(),
                items(project, baseline).stream().map(i -> new BaselineItemRefResponse(i.get("design_item_id").toString(),i.get("id").toString())).toList(),
                row.get("published_by").toString(), time(row.get("published_at")));
    }
    private static void requireOne(int count) {
        if (count != 1) throw conflict("version_conflict");
    }
}
