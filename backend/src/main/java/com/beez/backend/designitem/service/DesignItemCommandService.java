package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.ConfirmDesignItemSourceRequest;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.designitem.support.SourceLocators;
import com.beez.backend.designitem.validation.DesignContentValidator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
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

    /**
     * PLN-A11 시작된 프로젝트에서 팀장이 현재 기준의 설계 항목 출처 위치를 확정한다(S15P31A108-422).
     *
     * <p>출처 문서는 있는데 위치가 없거나, 문서가 바뀌어 위치를 옮기지 못한 항목은 근거 수집이 그 항목을 쓸 수 없어 그 항목을 참조하는
     * 작업 검증이 막힌다. 항목 버전은 그대로 두고 현재 기준의 유효 출처(baseline_item_source)만 기록하므로 작업 계획의 설계 참조는
     * 낡지 않고, 다음 기준 발행 때는 다른 유효 출처처럼 이어지거나 옮겨진다. 줄 범위는 현재 기준의 문서 버전 원문 안이어야 하고,
     * 근거 수집이 쓸 수 있는 크기여야 한다.
     */
    @Transactional
    public DesignItemResponse confirmSource(CallerContext caller, long item, ConfirmDesignItemSourceRequest input) {
        long project = Ids.parse(input.projectId(), "project_id");
        long version = Ids.parse(input.expectedVersionId(), "expected_version_id");
        long expectedDocument = Ids.parse(input.expectedDocumentVersionId(), "expected_document_version_id");
        boolean noQuote = Boolean.TRUE.equals(input.noQuote());
        boolean lines = input.startLine() != null || input.endLine() != null;
        if (noQuote == lines) {
            throw invalid("줄 범위(start_line·end_line)와 인용 없음(no_quote=true) 중 하나만 보내 주세요.");
        }
        if (lines && (input.startLine() == null || input.endLine() == null || input.startLine() < 1
                || input.endLine() < input.startLine())) {
            throw invalid("시작 줄과 끝 줄을 1 이상, 시작 ≤ 끝으로 보내 주세요.");
        }
        locks.leader(caller, project);
        if (!baselines.exists(project)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_required",
                    "기준 설계를 발행하기 전에는 초안 수정으로 출처를 고쳐 주세요.");
        }
        var row = baselines.currentItemSource(project, item)
                .orElseThrow(() -> AccessErrors.notFound("design_item_not_in_baseline"));
        if (((Number) row.get("design_item_version_id")).longValue() != version) {
            throw conflict("version_conflict");
        }
        if (row.get("source_document_version_id") == null) {
            throw new ApiException(HttpStatus.CONFLICT, "design_source_missing",
                    "원문 출처가 없는 항목이라 확정할 출처 위치가 없어요.");
        }
        if (row.get("document_version_id") == null) {
            throw new ApiException(HttpStatus.CONFLICT, "source_document_not_in_baseline",
                    "출처 문서가 현재 기준 설계에 들어 있지 않아요. 문서 상태를 확인해 주세요.");
        }
        long documentVersion = ((Number) row.get("document_version_id")).longValue();
        // 문서만 바꾸는 변경 제안은 항목 버전을 그대로 두고 유효 출처만 새 문서 버전으로 옮긴다. 화면이 읽은 원문과 지금 원문이
        // 다르면 옛 줄 번호가 새 원문에 저장되므로 거절하고 다시 읽게 한다(PR #299 리뷰).
        if (documentVersion != expectedDocument) {
            throw new ApiException(HttpStatus.CONFLICT, "source_document_changed",
                    "출처 문서가 새 버전으로 바뀌었어요. 원문을 다시 열어 위치를 지정해 주세요.");
        }
        String locator = noQuote ? SourceLocators.NO_QUOTE
                : lineLocator(row.get("content_text"), row.get("text_hash"), input.startLine(), input.endLine());
        baselines.confirmItemSource(((Number) row.get("baseline_id")).longValue(), project, item, documentVersion, locator,
                caller.accountId());
        return queries.get(project, item, version);
    }

    /** 원문이 근거 수집과 같은 규칙(정규화·해시)으로 확인될 때만 줄 범위를 만든다. */
    private static String lineLocator(Object text, Object storedHash, int start, int end) {
        if (!(text instanceof String content) || content.isEmpty() || content.startsWith("﻿") || content.indexOf('\r') >= 0
                || !(storedHash instanceof String hash) || !hash.equals(sha256(content))) {
            throw new ApiException(HttpStatus.CONFLICT, "source_text_unavailable",
                    "출처 문서의 원문을 확인하지 못했어요. 문서 처리 상태를 확인해 주세요.");
        }
        var all = SourceLocators.lines(content, start, end);
        if (all == null) {
            throw invalid("줄 범위가 문서 밖이거나 근거로 쓰기에 너무 커요. 문서 안의 줄을 " + SourceLocators.MAX_EXCERPT_BYTES
                    + "바이트 이하로 지정해 주세요.");
        }
        return all;
    }

    private static String sha256(String content) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
