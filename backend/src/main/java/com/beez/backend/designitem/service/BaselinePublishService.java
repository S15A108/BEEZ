package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.id;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.object;
import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.BaselineDocumentRefResponse;
import com.beez.backend.designitem.dto.BaselineItemRefResponse;
import com.beez.backend.designitem.dto.InitialBaselineResponse;
import com.beez.backend.designitem.dto.PublishInitialBaselineRequest;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.validation.DesignContentValidator;
import com.beez.backend.document.config.DocumentProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BaselinePublishService {
    private final BaselineRepository baselines;
    private final DesignProjectRepository projects;
    private final DesignProjectLock locks;
    private final DesignCommandService commands;
    private final DesignSnapshotService snapshots;
    private final DesignItemCommandService items;
    private final DesignContentValidator content;
    private final DocumentProperties properties;

    public BaselinePublishService(BaselineRepository baselines, DesignProjectRepository projects, DesignProjectLock locks, DesignCommandService commands,
                                   DesignSnapshotService snapshots, DesignItemCommandService items,
                                   DesignContentValidator content, DocumentProperties properties) {
        this.baselines = baselines;
        this.projects = projects;
        this.locks = locks;
        this.commands = commands;
        this.snapshots = snapshots;
        this.items = items;
        this.content = content;
        this.properties = properties;
    }

    @Transactional
    public InitialBaselineResponse publish(CallerContext caller, PublishInitialBaselineRequest input, String key) {
        long project = Ids.parse(input.projectId(), "project_id");
        List<Long> documentVersions = Ids.parseAll(input.documentVersionIds(), "document_version_ids");
        List<Long> itemVersions = Ids.parseAll(input.designItemVersionIds(), "design_item_version_ids");
        var command = Map.of("project_id", project, "document_version_ids", documentVersions,
                "design_item_version_ids", itemVersions, "draft_snapshot_hash", input.draftSnapshotHash());
        locks.leader(caller, project);
        // Replaying a successful command must work even after its baseline has become current.
        var previous = commands.replay(project, caller.accountId(), "initial-baseline", key, command, InitialBaselineResponse.class);
        if (previous.isPresent()) {
            return previous.get();
        }
        items.requireInitial(project);
        var documents = snapshots.documents(project);
        var drafts = snapshots.items(project);
        if (documents.size() != 4 || !documents.stream().map(r -> r.get("type").toString()).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("PLAN", "REQ_SPEC", "ERD", "API_SPEC"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "required_documents_missing", "필수 문서 4종이 필요합니다.");
        }
        if (!sameIds(documentVersions, documents) || drafts.isEmpty()
                || !sameIds(itemVersions, drafts) || !snapshots.hash(project).equals(input.draftSnapshotHash())) {
            throw conflict("draft_changed");
        }
        if (baselines.hasUnapprovedWorkingCopies(project)) throw conflict("document_not_approved");
        for (var document : documents) {
            if (!"APPROVED".equals(document.get("status"))) {
                throw conflict("document_not_approved");
            }
            if (document.get("content_text") == null || document.get("content_text").toString().isBlank()) {
                throw invalid("문서 본문이 필요합니다.");
            }
            requireReady(id(document, "id"));
        }
        var documentIds = new HashSet<>(documentVersions);
        for (var draft : drafts) {
            var body = object(draft.get("content"));
            content.validate(draft.get("kind").toString(), ((Number) draft.get("schema_version")).intValue(), body);
            if (!hash(body).equals(draft.get("content_hash"))) {
                throw conflict("draft_changed");
            }
            if (draft.get("source_document_version_id") != null
                    && !documentIds.contains(id(draft, "source_document_version_id"))) {
                throw invalid("항목 출처는 발행할 문서 버전이어야 합니다.");
            }
        }
        var baseline = baselines.insertInitial(project, caller.accountId());
        long baselineId = id(baseline, "id");
        for (var document : documents) {
            long version = id(document, "id");
            baselines.insertDocumentRef(baselineId, project, id(document, "document_id"), version);
        }
        for (var draft : drafts) {
            long version = id(draft, "id");
            int changed = baselines.approveItem(version);
            if (changed != 1) {
                throw conflict("draft_changed");
            }
            baselines.insertItemRef(baselineId, project, id(draft, "design_item_id"), version);
        }
        int changed = projects.setInitialBaseline(project, baselineId);
        if (changed != 1) {
            throw conflict("baseline_exists");
        }
        var result = new InitialBaselineResponse(Long.toString(baselineId), Long.toString(project), 1,
                documents.stream().map(r -> new BaselineDocumentRefResponse(r.get("document_id").toString(), r.get("id").toString())).toList(),
                drafts.stream().map(r -> new BaselineItemRefResponse(r.get("design_item_id").toString(), r.get("id").toString())).toList(),
                caller.accountId().toString(), time(baseline.get("published_at")));
        commands.save(project, caller.accountId(), "initial-baseline", key, command, result);
        return result;
    }

    private boolean sameIds(List<Long> ids, List<Map<String, Object>> rows) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            return false;
        }
        var set = new HashSet<>(ids);
        return set.size() == ids.size() && set.equals(rows.stream().map(r -> id(r, "id")).collect(java.util.stream.Collectors.toSet()));
    }

    private void requireReady(long version) {
        boolean ready = baselines.isIndexReady(version, properties.embeddingProfile());
        if (!ready) {
            throw conflict("analysis_not_completed");
        }
    }
}
