package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.designitem.validation.DesignContentValidator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignItemCommandService {
    private final DesignItemRepository items;
    private final BaselineRepository baselines;
    private final DesignProjectLock locks;
    private final DesignCommandService commands;
    private final DesignContentValidator content;
    private final DesignItemQueryService queries;
    private final DesignSnapshotService snapshots;

    public DesignItemCommandService(DesignItemRepository items, BaselineRepository baselines, DesignProjectLock locks,
                                    DesignCommandService commands, DesignContentValidator content, DesignItemQueryService queries,
                                    DesignSnapshotService snapshots) {
        this.items = items;
        this.baselines = baselines;
        this.locks = locks;
        this.commands = commands;
        this.content = content;
        this.queries = queries;
        this.snapshots = snapshots;
    }

    @Transactional
    public DesignItemResponse create(CallerContext caller, long project, CreateDesignItemRequest input, String key) {
        locks.leader(caller, project);
        Long source = Ids.parse(input.sourceDocumentVersionId(), "source_document_version_id");
        // Hash normalized command input with parsed IDs and explicit optional fields.
        var command = new HashMap<String, Object>();
        command.put("kind", input.kind());
        command.put("schema_version", input.schemaVersion());
        command.put("content", input.content());
        command.put("source_document_version_id", source);
        command.put("source_location", input.sourceLocation());
        var previous = commands.replay(project, caller.accountId(), "create-item", key, command, DesignItemResponse.class);
        if (previous.isPresent()) {
            return previous.get();
        }
        requireInitial(project);
        String title = content.validate(input.kind(), input.schemaVersion(), input.content());
        if (source != null) {
            queries.requireSource(project, source);
        } else if (input.sourceLocation() != null) {
            throw invalid("source_location에는 원문 버전이 필요합니다.");
        }
        String prefix = switch (input.kind()) {
            case "REQUIREMENT" -> "REQ";
            case "API_CONTRACT" -> "API";
            case "DATA_CONTRACT" -> "ENT";
            case "POLICY" -> "POL";
            default -> "CON";
        };
        long sequence = items.nextKeySequence(project, prefix);
        String displayKey = prefix + "-" + String.format(Locale.ROOT, "%03d", sequence);
        long item = items.insertItem(project, displayKey, input.kind());
        String locator = source == null ? null : canonical(Map.of("location", Objects.toString(input.sourceLocation(), "")));
        long version = items.insertDraft(item, title, canonical(input.content()), hash(input.content()), source, locator, caller.accountId());
        var result = queries.get(project, item, version);
        commands.save(project, caller.accountId(), "create-item", key, command, result);
        return result;
    }

    @Transactional
    public DesignItemResponse update(CallerContext caller, long item, UpdateDesignItemRequest input) {
        long project = Ids.parse(input.projectId(), "project_id");
        long version = Ids.parse(input.expectedVersionId(), "expected_version_id");
        locks.leader(caller, project);
        requireInitial(project);
        var current = editable(project, item, version, input.expectedContentHash());
        String title = content.validate(current.kind(), current.schemaVersion(), input.content());
        if (Boolean.TRUE.equals(input.replaceSource())) {
            if (input.expectedDraftSnapshotHash() == null || !input.expectedDraftSnapshotHash().equals(snapshots.hash(project))) {
                throw conflict("draft_changed");
            }
            Long source = Ids.parse(input.sourceDocumentVersionId(), "source_document_version_id");
            if (source != null) queries.requireSource(project, source);
            else if (input.sourceLocation() != null) throw invalid("source_location에는 원문 버전이 필요합니다.");
            items.updateDraftSource(item, version, source,
                    source == null || input.sourceLocation() == null ? null : canonical(Map.of("location", input.sourceLocation())));
        } else if (input.sourceDocumentVersionId() != null || input.sourceLocation() != null) {
            throw invalid("출처 변경에는 replace_source=true가 필요합니다.");
        }
        int changed = items.updateDraft(item, version, title, canonical(input.content()), hash(input.content()), input.expectedContentHash());
        if (changed != 1) {
            throw conflict("version_conflict");
        }
        return queries.get(project, item, version);
    }

    @Transactional
    public void exclude(CallerContext caller, long project, long item, long version, String expectedHash) {
        locks.leader(caller, project);
        requireInitial(project);
        editable(project, item, version, expectedHash);
        if (items.excludeDraft(item, version, expectedHash) != 1) {
            throw conflict("version_conflict");
        }
    }

    private DesignItemResponse editable(long project, long item, long version, String expectedHash) {
        if (item < 1 || version < 1 || expectedHash == null || expectedHash.isBlank()) {
            throw invalid("대상 버전과 해시가 필요합니다.");
        }
        var current = queries.get(project, item, version);
        if (!"DRAFT".equals(current.status()) || !Objects.equals(current.contentHash(), expectedHash)) {
            throw conflict("version_conflict");
        }
        if (!items.isActive(item)) {
            throw AccessErrors.notFound("retired_design_item");
        }
        return current;
    }

    void requireInitial(long project) {
        if (baselines.exists(project)) {
            throw conflict("baseline_exists");
        }
    }
}
