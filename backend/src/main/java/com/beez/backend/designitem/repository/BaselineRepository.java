package com.beez.backend.designitem.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BaselineRepository {
    private final JdbcClient jdbc;

    public BaselineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long project) {
        return jdbc.sql("SELECT id FROM baseline WHERE project_id=:project LIMIT 1")
                .param("project", project).query(Long.class).optional().isPresent();
    }

    public List<Map<String, Object>> findLatestDocuments(long project) {
        return jdbc.sql("""
                SELECT DISTINCT ON (d.id) d.id AS document_id,d.type,v.id,v.version_no,v.status,
                       v.content_hash,v.content_text,v.parent_version_id
                  FROM document d JOIN document_version v ON v.document_id=d.id
                 WHERE d.project_id=:project AND v.status IN ('DRAFT','APPROVED')
                   AND (NOT EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id)
                        OR EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id AND w.initial_version_id=v.id)
                        OR EXISTS (SELECT 1 FROM document_review_submission s WHERE s.document_version_id=v.id))
                 ORDER BY d.id,v.version_no DESC
                """).param("project", project).query().listOfRows();
    }

    public Map<String, Object> insertInitial(long project, long actor) {
        return jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:project,1,:actor) RETURNING id,published_at")
                .params(Map.of("project", project, "actor", actor)).query().singleRow();
    }

    public List<Map<String, Object>> workingCopyStates(long project) {
        return jdbc.sql("SELECT w.document_id,w.revision,w.status FROM document_working_copy w JOIN document d ON d.id=w.document_id WHERE d.project_id=:project ORDER BY w.document_id")
                .param("project", project).query().listOfRows();
    }

    public boolean hasUnapprovedWorkingCopies(long project) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_working_copy w JOIN document d ON d.id=w.document_id WHERE d.project_id=:project AND w.status<>'APPROVED')")
                .param("project", project).query(Boolean.class).single();
    }

    public int approveItem(long version) {
        return jdbc.sql("UPDATE design_item_version SET status='APPROVED',approved_at=now(),updated_at=now() WHERE id=:id AND status='DRAFT'")
                .param("id", version).update();
    }

    public void insertDocumentRef(long baseline, long project, long document, long version) {
        jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:baseline,:project,:document,:version)")
                .params(Map.of("baseline", baseline, "project", project, "document", document, "version", version)).update();
    }

    public void insertItemRef(long baseline, long project, long item, long version) {
        jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:baseline,:project,:item,:version)")
                .params(Map.of("baseline", baseline, "project", project, "item", item, "version", version)).update();
    }

    /** A version's index is ready when its current attempt published every chunk with an embedding for the profile. */
    private static final String READY_INDEX = """
            SELECT 1 FROM document_processing p JOIN document_index_manifest m USING(document_version_id)
            WHERE p.document_version_id=%s AND p.status='COMPLETED' AND p.source_status='READY'
              AND p.index_status='READY' AND m.attempt=p.attempt AND m.text_hash=p.text_hash
              AND m.embedding_profile_id=:profile AND m.chunk_count>0
              AND m.chunk_count=(SELECT count(*) FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NULL)
            """;

    public boolean isIndexReady(long version, String profile) {
        return jdbc.sql("SELECT EXISTS (" + READY_INDEX.formatted(":version") + ")")
                .params(Map.of("version", version, "profile", profile)).query(Boolean.class).single();
    }

    /** Baseline documents whose index is not ready; semantic search over the baseline requires zero. */
    public int countDocumentsWithoutReadyIndex(long baseline, String profile) {
        return jdbc.sql("SELECT count(*) FROM baseline_document b WHERE b.baseline_id=:baseline AND NOT EXISTS (" + READY_INDEX.formatted("b.document_version_id") + ")")
                .params(Map.of("baseline", baseline, "profile", profile)).query(Integer.class).single();
    }
}
