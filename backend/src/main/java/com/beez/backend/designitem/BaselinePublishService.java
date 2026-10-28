package com.beez.backend.designitem;

import static com.beez.backend.designitem.DesignJson.*;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.config.DocumentProperties;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BaselinePublishService {
    private final JdbcClient jdbc;
    private final DesignProjectLock locks;
    private final DesignCommandStore commands;
    private final DesignSnapshot snapshots;
    private final DesignItemCommandService items;
    private final DesignContentValidator content;
    private final DocumentProperties properties;
    public BaselinePublishService(JdbcClient jdbc, DesignProjectLock locks, DesignCommandStore commands,
                                   DesignSnapshot snapshots, DesignItemCommandService items,
                                   DesignContentValidator content, DocumentProperties properties) {
        this.jdbc = jdbc; this.locks = locks; this.commands = commands; this.snapshots = snapshots;
        this.items = items; this.content = content; this.properties = properties;
    }

    @Transactional
    public DesignItemDtos.Baseline publish(CallerContext caller, DesignItemDtos.Publish input, String key) {
        long project = input.project_id();
        locks.leader(caller, project);
        // Replaying a successful command must work even after its baseline has become current.
        var previous = commands.replay(project, caller.accountId(), "initial-baseline", key, input, DesignItemDtos.Baseline.class);
        if (previous.isPresent()) return previous.get();
        items.requireInitial(project);
        var documents = snapshots.documents(project);
        var drafts = snapshots.items(project);
        if (documents.size() != 4 || !documents.stream().map(r -> r.get("type").toString()).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("PLAN", "REQ_SPEC", "ERD", "API_SPEC"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "required_documents_missing", "필수 문서 4종이 필요합니다.");
        }
        if (!sameIds(input.document_version_ids(), documents) || drafts.isEmpty()
                || !sameIds(input.design_item_version_ids(), drafts) || !snapshots.hash(project).equals(input.draft_snapshot_hash())) {
            throw conflict("draft_changed");
        }
        for (var document : documents) {
            if (document.get("content_text") == null || document.get("content_text").toString().isBlank()) throw invalid("문서 본문이 필요합니다.");
            requireReady(id(document, "id"));
        }
        var documentIds = new HashSet<>(input.document_version_ids());
        for (var draft : drafts) {
            var body = object(draft.get("content"));
            content.validate(draft.get("kind").toString(), ((Number) draft.get("schema_version")).intValue(), body);
            if (!hash(body).equals(draft.get("content_hash"))) throw conflict("draft_changed");
            if (draft.get("source_document_version_id") != null
                    && !documentIds.contains(id(draft, "source_document_version_id"))) throw invalid("항목 출처는 발행할 문서 버전이어야 합니다.");
        }
        var baseline = jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:project,1,:actor) RETURNING id,published_at")
                .params(Map.of("project", project, "actor", caller.accountId())).query().singleRow();
        long baselineId = id(baseline, "id");
        for (var document : documents) {
            long version = id(document, "id");
            int changed = jdbc.sql("UPDATE document_version SET status='APPROVED',approved_by=:actor,approved_at=now(),updated_at=now(),lock_version=lock_version+1 WHERE id=:id AND status='DRAFT'")
                    .params(Map.of("actor", caller.accountId(), "id", version)).update();
            if (changed != 1) throw conflict("draft_changed");
            jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:baseline,:project,:document,:version)")
                    .params(Map.of("baseline", baselineId, "project", project, "document", id(document, "document_id"), "version", version)).update();
        }
        for (var draft : drafts) {
            long version = id(draft, "id");
            int changed = jdbc.sql("UPDATE design_item_version SET status='APPROVED',approved_at=now(),updated_at=now() WHERE id=:id AND status='DRAFT'")
                    .param("id", version).update();
            if (changed != 1) throw conflict("draft_changed");
            jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:baseline,:project,:item,:version)")
                    .params(Map.of("baseline", baselineId, "project", project, "item", id(draft, "design_item_id"), "version", version)).update();
        }
        int changed = jdbc.sql("UPDATE project SET current_baseline_id=:baseline,updated_at=now() WHERE id=:project AND current_baseline_id IS NULL")
                .params(Map.of("baseline", baselineId, "project", project)).update();
        if (changed != 1) throw conflict("baseline_exists");
        var result = new DesignItemDtos.Baseline(Long.toString(baselineId), Long.toString(project), 1,
                documents.stream().map(r -> new DesignItemDtos.DocumentRef(r.get("document_id").toString(), r.get("id").toString())).toList(),
                drafts.stream().map(r -> new DesignItemDtos.ItemRef(r.get("design_item_id").toString(), r.get("id").toString())).toList(),
                caller.accountId().toString(), time(baseline.get("published_at")));
        commands.save(project, caller.accountId(), "initial-baseline", key, input, result);
        return result;
    }

    private boolean sameIds(List<Long> ids, List<Map<String, Object>> rows) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) return false;
        var set = new HashSet<>(ids);
        return set.size() == ids.size() && set.equals(rows.stream().map(r -> id(r, "id")).collect(java.util.stream.Collectors.toSet()));
    }
    private void requireReady(long version) {
        boolean ready = jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM document_processing p JOIN document_index_manifest m USING(document_version_id)
                  WHERE p.document_version_id=:version AND p.status='COMPLETED' AND p.source_status='READY'
                    AND p.index_status='READY' AND m.attempt=p.attempt AND m.text_hash=p.text_hash
                    AND m.embedding_profile_id=:profile AND m.chunk_count>0
                    AND m.chunk_count=(SELECT count(*) FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NOT NULL)
                    AND NOT EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NULL)
                )
                """).params(Map.of("version", version, "profile", properties.embeddingProfile())).query(Boolean.class).single();
        if (!ready) throw conflict("analysis_not_completed");
    }
}
