package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.*;
import static com.beez.backend.changeproposal.validation.ProposalValidator.requiredId;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.changeproposal.validation.ProposalValidator;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.designitem.validation.DesignContentValidator;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalCommandService {
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final ProposalRepository repository;
    private final ProposalValidator validator;
    private final DesignContentValidator contents;
    private final DesignCommandService idempotency;
    private final ProposalQueryService queries;
    public ProposalCommandService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository repository,
            ProposalValidator validator, DesignContentValidator contents, DesignCommandService idempotency, ProposalQueryService queries) {
        this.access=access; this.projects=projects; this.repository=repository; this.validator=validator;
        this.contents=contents; this.idempotency=idempotency; this.queries=queries;
    }

    @Transactional
    public Proposal create(CallerContext caller, long project, Input input, String key) {
        lock(caller, project); validator.validate(input, false);
        String operation = operation(caller, "create");
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        if (input.previousProposalId() != null) {
            var previous = repository.proposal(project, Long.parseLong(input.previousProposalId()));
            if (id(previous, "proposer_account_id") != caller.accountId())
                throw AccessErrors.forbidden("proposal_author_required", "본인이 거둔 제안만 새 제안에 연결할 수 있습니다.");
            if (!"WITHDRAWN".equals(previous.get("status")))
                throw error("invalid_transition", "거둔 제안만 새 제안에 연결할 수 있습니다. 반려된 제안은 같은 번호에서 재제출해 주세요.");
        }
        Snapshot snapshot = resolve(caller, project, null, input);
        long proposal = repository.create(project, caller, snapshot.input());
        repository.saveRevision(project, proposal, 1, caller, snapshot, hash(snapshot));
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        // [TODO] 알림·타임라인은 전진 담당 공통 서비스 계약 확정 후 연결한다.
        return response;
    }

    @Transactional
    public Proposal resubmit(CallerContext caller, long project, long proposal, Input input, String key) {
        lock(caller, project); validator.validate(input, true);
        var current = repository.proposal(project, proposal);
        if (id(current, "proposer_account_id") != caller.accountId())
            throw AccessErrors.forbidden("proposal_author_required", "제안한 담당자만 반려 내용을 보완해 재제출할 수 있습니다.");
        String operation = operation(caller, "resubmit:" + proposal);
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        if (((Number) current.get("revision_no")).intValue() != input.expectedRevision()
                || !current.get("snapshot_hash").equals(input.expectedSnapshotHash()))
            throw error("proposal_changed", "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요.");
        if (!"REJECTED".equals(current.get("status")))
            throw error("proposal_locked", "제출한 제안은 잠겨 있습니다. 반려된 뒤에만 보완해 재제출할 수 있습니다.");
        if (current.get("rejection_reason") == null)
            throw error("proposal_decision_missing", "반려 결정 이력을 확인할 수 없습니다. 담당자에게 확인해 주세요.");
        Snapshot snapshot = resolve(caller, project, proposal, input);
        int revision = Math.addExact(input.expectedRevision(), 1);
        repository.advance(proposal, snapshot.input());
        repository.saveRevision(project, proposal, revision, caller, snapshot, hash(snapshot));
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        return response;
    }

    private void lock(CallerContext caller, long project) {
        if (project < 1) throw invalid("프로젝트 ID는 양수여야 합니다.");
        access.requireMember(caller, project);
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("proposal_project_missing");
        repository.lockCaller(project, caller); // Fresh SQL after waiting; avoid stale JPA membership entities.
        access.requireMember(caller, project);
    }

    @Transactional
    public Proposal withdraw(CallerContext caller, long project, long proposal, WithdrawRequest input, String key) {
        lock(caller, project); validator.validate(input);
        var current = repository.proposal(project, proposal);
        if (id(current, "proposer_account_id") != caller.accountId())
            throw AccessErrors.forbidden("proposal_author_required", "제안한 담당자만 변경 제안을 거둘 수 있습니다.");
        String operation = operation(caller, "withdraw:" + proposal);
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        if (((Number) current.get("revision_no")).intValue() != input.expectedRevision()
                || !current.get("snapshot_hash").equals(input.expectedSnapshotHash()))
            throw error("proposal_changed", "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요.");
        if (!Set.of("PROPOSED", "IN_REVIEW").contains(current.get("status")))
            throw error("invalid_transition", "제출 또는 검토 중인 변경 제안만 거둘 수 있습니다.");
        repository.withdraw(project, proposal, caller.accountId());
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        // [TODO] 거두기 알림·타임라인은 전진 담당 공통 서비스 계약 확정 후 연결한다.
        return response;
    }

    private Snapshot resolve(CallerContext caller, long project, Long proposal, Input input) {
        long baseline = requiredId(input.baseBaselineId(), "base_baseline_id");
        if (projects.currentBaselineId(project) == null)
            throw error("baseline_required", "최초 기준 설계를 생성한 뒤 변경 제안을 제출해 주세요.");
        if (!projects.baselineBelongsToProject(project, baseline)) throw AccessErrors.notFound("proposal_baseline_missing");
        if (!Objects.equals(projects.currentBaselineId(project), baseline))
            throw error("baseline_conflict", "현재 기준 설계가 변경되었습니다. 최신 기준으로 제안을 다시 검토해 주세요.");
        repository.requireRelated(project, Ids.parse(input.taskId(), "task_id"), Ids.parse(input.validationId(), "validation_id"));
        var items = new ArrayList<Item>();
        var existingIds = new HashSet<Long>();
        for (Item item : input.items()) if (item.designItemId() != null) existingIds.add(Long.valueOf(item.designItemId()));
        var bases = repository.baseItems(project, baseline, existingIds);
        boolean changed = false;
        for (Item item : input.items()) {
            String kind = item.kind();
            Map<String, Object> base = null;
            if (!"CREATE".equals(item.operation())) {
                base = bases.get(requiredId(item.designItemId(), "design_item_id"));
                if (base == null) throw AccessErrors.notFound("proposal_item_missing");
                if (id(base, "id") != requiredId(item.baseDesignItemVersionId(), "base_design_item_version_id"))
                    throw error("baseline_conflict", "항목의 기준 버전이 기준 설계와 일치하지 않습니다.");
                String existingKind = base.get("kind").toString();
                if (kind != null && !kind.equals(existingKind)) throw invalid("기존 항목 종류를 변경할 수 없습니다.");
                kind = existingKind;
            }
            if (!"RETIRE".equals(item.operation())) contents.validate(kind, item.schemaVersion(), item.proposedContent());
            if (base == null || "RETIRE".equals(item.operation()) || !canonical(object(base.get("content"))).equals(canonical(item.proposedContent()))) changed = true;
            items.add(new Item(item.operation(), item.designItemId(), item.baseDesignItemVersionId(), kind,
                    item.schemaVersion(), item.proposedContent()));
        }
        var documents = new ArrayList<ResolvedDocument>();
        for (DocumentChange doc : input.documents()) {
            long document = requiredId(doc.documentId(), "document_id");
            long version = requiredId(doc.baseDocumentVersionId(), "base_document_version_id");
            var base = repository.baseDocument(project, baseline, document);
            if (id(base, "id") != version) throw error("baseline_conflict", "문서의 기준 버전이 기준 설계와 일치하지 않습니다.");
            String after = doc.afterContent();
            if (doc.proposedDocumentVersionId() != null) {
                var draft = repository.draft(project, document, requiredId(doc.proposedDocumentVersionId(), "proposed_document_version_id"));
                if (!"DRAFT".equals(draft.get("status")) || draft.get("parent_version_id") == null
                        || id(draft, "parent_version_id") != version)
                    throw invalid("지정한 기준 버전에서 만든 DRAFT 문서만 제출할 수 있습니다.");
                if (draft.get("created_by") == null || id(draft, "created_by") != caller.accountId())
                    throw AccessErrors.forbidden("proposal_document_author", "본인이 업로드한 수정본만 제출할 수 있습니다.");
                if (draft.get("change_proposal_id") != null && !Objects.equals(id(draft, "change_proposal_id"), proposal))
                    throw error("document_already_proposed", "이미 다른 변경 제안에 연결된 수정본입니다.");
                after = (String) draft.get("content_text");
            }
            ProposalValidator.documentText(after);
            // Preserve exact supplied text; uploading files already applies the document normalizer.
            if (!Objects.equals(base.get("content_text"), after)) changed = true;
            documents.add(new ResolvedDocument(doc.documentId(), doc.baseDocumentVersionId(), doc.proposedDocumentVersionId(), after,
                    textHash(after)));
        }
        if (!changed) throw invalid("기준과 다른 문서 또는 설계 항목 변경이 하나 이상 필요합니다.");
        Input normalized = new Input(input.baseBaselineId(), input.title(), input.reason(), input.taskId(), input.validationId(),
                List.copyOf(items), List.copyOf(input.documents()), null, null,
                proposal == null ? input.previousProposalId() : nullablePrevious(project, proposal));
        Snapshot snapshot = new Snapshot(normalized, List.copyOf(documents), false);
        canonical(snapshot);
        return snapshot;
    }
    private String nullablePrevious(long project, long proposal) {
        Object previous = repository.proposal(project, proposal).get("previous_proposal_id");
        return previous == null ? null : previous.toString();
    }
    private static String operation(CallerContext caller, String command) {
        return "proposal:" + command + ":" + (caller.isHuman() ? "human" : "agent:" + caller.agentTokenId());
    }
    private static String textHash(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static ApiException error(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
