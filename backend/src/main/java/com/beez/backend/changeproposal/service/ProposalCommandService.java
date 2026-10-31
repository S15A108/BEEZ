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
import com.beez.backend.document.service.DocumentOriginalWriter;
import com.beez.backend.document.service.DocumentOriginalWriter.Original;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProposalCommandService {
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final ProposalRepository repository;
    private final ProposalValidator validator;
    private final DesignContentValidator contents;
    private final DesignCommandService idempotency;
    private final ProposalQueryService queries;
    private final DocumentInputValidator documentInputs;
    private final DocumentOriginalWriter originals;
    private final TransactionTemplate transaction;
    public ProposalCommandService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository repository,
            ProposalValidator validator, DesignContentValidator contents, DesignCommandService idempotency, ProposalQueryService queries,
            DocumentInputValidator documents, DocumentStorage storage, PlatformTransactionManager manager) {
        this.access=access; this.projects=projects; this.repository=repository; this.validator=validator;
        this.contents=contents; this.idempotency=idempotency; this.queries=queries; this.documentInputs=documents;
        this.originals=new DocumentOriginalWriter(storage, manager); this.transaction=new TransactionTemplate(manager);
    }

    /** A proposed text that must be stored as a document version before the proposal transaction starts. */
    private record Staged(String text, String textHash, byte[] bytes, String extension) { }
    private record Stage(List<Staged> files, Map<Integer, Integer> byDocument) {
        List<DocumentOriginalWriter.Payload> payloads() {
            return files.stream().map(file -> new DocumentOriginalWriter.Payload(file.bytes(), file.extension())).toList();
        }
    }

    public Proposal create(CallerContext caller, long project, Input input, String key) {
        preflight(caller, project, input, false);
        String operation = operation(caller, "create");
        var replayed = replayed(project, caller, operation, key, input);
        if (replayed.isPresent()) return replayed.get();
        requirePrevious(caller, project, input);
        Stage stage = stage(project, input);
        return publish(stage, stored -> createLocked(caller, project, input, key, operation, stage, stored));
    }

    private Proposal createLocked(CallerContext caller, long project, Input input, String key, String operation,
            Stage stage, List<Original> stored) {
        lock(caller, project);
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        requirePrevious(caller, project, input);
        Snapshot snapshot = resolve(caller, project, null, input, stage, stored);
        long proposal = repository.create(project, caller, snapshot.input());
        repository.saveRevision(project, proposal, 1, caller, snapshot, hash(snapshot));
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        // [TODO] 알림·타임라인은 전진 담당 공통 서비스 계약 확정 후 연결한다.
        return response;
    }

    public Proposal resubmit(CallerContext caller, long project, long proposal, Input input, String key) {
        preflight(caller, project, input, true);
        String operation = operation(caller, "resubmit:" + proposal);
        var current = repository.proposal(project, proposal);
        requireAuthor(current, caller);
        var replayed = replayed(project, caller, operation, key, input);
        if (replayed.isPresent()) return replayed.get();
        // Reject stale or locked requests before any original is written to storage.
        requireResubmittable(current, input);
        Stage stage = stage(project, input);
        return publish(stage, stored -> resubmitLocked(caller, project, proposal, input, key, operation, stage, stored));
    }

    private Proposal resubmitLocked(CallerContext caller, long project, long proposal, Input input, String key, String operation,
            Stage stage, List<Original> stored) {
        lock(caller, project);
        var current = repository.proposal(project, proposal);
        requireAuthor(current, caller);
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        requireResubmittable(current, input);
        Snapshot snapshot = resolve(caller, project, proposal, input, stage, stored);
        int revision = Math.addExact(input.expectedRevision(), 1);
        repository.advance(proposal, snapshot.input());
        repository.saveRevision(project, proposal, revision, caller, snapshot, hash(snapshot));
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        return response;
    }

    /** Cheap checks before any original is written: membership first, then input shape. */
    private void preflight(CallerContext caller, long project, Input input, boolean update) {
        if (project < 1) throw invalid("프로젝트 ID는 양수여야 합니다.");
        access.requireMember(caller, project);
        validator.validate(input, update);
    }

    private Optional<Proposal> replayed(long project, CallerContext caller, String operation, String key, Input input) {
        return transaction.execute(status -> idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class));
    }

    private void requirePrevious(CallerContext caller, long project, Input input) {
        if (input.previousProposalId() == null) return;
        var previous = repository.proposal(project, Long.parseLong(input.previousProposalId()));
        if (id(previous, "proposer_account_id") != caller.accountId())
            throw AccessErrors.forbidden("proposal_author_required", "본인이 거둔 제안만 새 제안에 연결할 수 있습니다.");
        if (!"WITHDRAWN".equals(previous.get("status")))
            throw error("invalid_transition", "거둔 제안만 새 제안에 연결할 수 있습니다. 반려된 제안은 같은 번호에서 재제출해 주세요.");
    }

    private static void requireAuthor(Map<String, Object> current, CallerContext caller) {
        if (id(current, "proposer_account_id") != caller.accountId())
            throw AccessErrors.forbidden("proposal_author_required", "제안한 담당자만 반려 내용을 보완해 재제출할 수 있습니다.");
    }

    private static void requireResubmittable(Map<String, Object> current, Input input) {
        if (((Number) current.get("revision_no")).intValue() != input.expectedRevision()
                || !current.get("snapshot_hash").equals(input.expectedSnapshotHash()))
            throw error("version_conflict", "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요.");
        if (!"REJECTED".equals(current.get("status")))
            throw error("proposal_locked", "제출한 제안은 잠겨 있습니다. 반려된 뒤에만 보완해 재제출할 수 있습니다.");
        if (current.get("rejection_reason") == null)
            throw error("proposal_decision_missing", "반려 결정 이력을 확인할 수 없습니다. 담당자에게 확인해 주세요.");
    }

    /**
     * Normalizes every after_content text exactly like a DOC-A12 upload (BOM, CR/LF) so the stored version, its index
     * hash and the compared text are identical. Nothing is written here.
     */
    private Stage stage(long project, Input input) {
        var files = new ArrayList<Staged>();
        var byDocument = new HashMap<Integer, Integer>();
        if (input.documents().stream().noneMatch(doc -> doc.afterContent() != null)) return new Stage(files, byDocument);
        // Report a missing or moved baseline before touching storage, as the transactional check would.
        requireCurrentBaseline(project, requiredId(input.baseBaselineId(), "base_baseline_id"));
        for (int index = 0; index < input.documents().size(); index++) {
            DocumentChange doc = input.documents().get(index);
            if (doc.afterContent() == null) continue;
            var base = repository.versionText(project, requiredId(doc.documentId(), "document_id"),
                    requiredId(doc.baseDocumentVersionId(), "base_document_version_id"))
                    .orElseThrow(() -> AccessErrors.notFound("proposal_reference_missing"));
            var text = documentInputs.validate("document." + base.extension(), doc.afterContent().getBytes(StandardCharsets.UTF_8));
            // Reject before writing anything: a document identical to its base would only cost a version and an index run.
            requireChanged(base.text(), text.text());
            byte[] bytes = text.text().getBytes(StandardCharsets.UTF_8);
            byDocument.put(index, files.size());
            files.add(new Staged(text.text(), text.textHash(), bytes, base.extension()));
        }
        return new Stage(files, byDocument);
    }

    private static void requireChanged(String base, String proposed) {
        if (Objects.equals(base, proposed)) throw invalid("기준 본문과 같은 문서는 변경 목록에서 빼 주세요.");
    }

    /** Originals go to storage outside the DB transaction; the proposal rows and the versions commit together. */
    private <T> T publish(Stage stage, Function<List<Original>, T> body) {
        if (stage.files().isEmpty()) return transaction.execute(status -> body.apply(List.of()));
        return originals.writeAll(stage.payloads(), body);
    }

    private void requireCurrentBaseline(long project, long baseline) {
        if (projects.currentBaselineId(project) == null)
            throw error("baseline_missing", "최초 기준 설계를 생성한 뒤 변경 제안을 제출해 주세요.");
        if (!projects.baselineBelongsToProject(project, baseline)) throw AccessErrors.notFound("proposal_baseline_missing");
        if (!Objects.equals(projects.currentBaselineId(project), baseline))
            throw error("baseline_conflict", "현재 기준 설계가 변경되었습니다. 최신 기준으로 제안을 다시 검토해 주세요.");
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
            throw error("version_conflict", "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요.");
        if (!Set.of("PROPOSED", "IN_REVIEW", "REJECTED").contains(current.get("status")))
            throw error("invalid_transition", "제출, 검토 중 또는 반려된 변경 제안만 거둘 수 있습니다.");
        repository.withdraw(project, proposal, caller.accountId());
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        // [TODO] 거두기 알림·타임라인은 전진 담당 공통 서비스 계약 확정 후 연결한다.
        return response;
    }

    /** CHG-A08. Re-requesting a review of the same revision is a no-op success. */
    @Transactional
    public Proposal startReview(CallerContext caller, long project, long proposal, WithdrawRequest input, String key) {
        validator.validate(input);
        return decide(caller, project, proposal, "review", input, key, input.expectedRevision(), input.expectedSnapshotHash(),
                current -> {
                    if ("IN_REVIEW".equals(current.get("status"))) return;
                    requireDecidable(current, Set.of("PROPOSED"), "제출된 변경 제안만 검토를 시작할 수 있습니다.");
                    repository.startReview(project, proposal);
                });
    }

    /** CHG-A10. The decision row and the status change commit together; CHG-A04 needs the stored reason. */
    @Transactional
    public Proposal reject(CallerContext caller, long project, long proposal, RejectRequest input, String key) {
        validator.validate(input);
        return decide(caller, project, proposal, "reject", input, key, input.expectedRevision(), input.expectedSnapshotHash(),
                current -> {
                    requireDecidable(current, Set.of("PROPOSED", "IN_REVIEW"), "제출되었거나 검토 중인 변경 제안만 반려할 수 있습니다.");
                    repository.reject(project, proposal, ((Number) current.get("revision_no")).intValue(),
                            id(current, "base_baseline_id"), caller.accountId(), input.reason().strip());
                });
    }

    /**
     * Shared by the leader decisions (CHG-A08/A10, and A09 later): human leader -> project lock -> leader recheck ->
     * proposal -> idempotent replay -> expected revision/hash -> {@code apply} (state check and write) -> response.
     */
    private Proposal decide(CallerContext caller, long project, long proposal, String command, Object input, String key,
            int expectedRevision, String expectedHash, java.util.function.Consumer<Map<String, Object>> apply) {
        if (project < 1) throw invalid("프로젝트 ID는 양수여야 합니다.");
        var actor = access.requireHumanLeader(caller, project);
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("proposal_project_missing");
        access.requireLeaderAfterLock(actor);
        var current = repository.proposal(project, proposal);
        String operation = operation(caller, command + ":" + proposal);
        var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Proposal.class);
        if (replay.isPresent()) return replay.get();
        if (((Number) current.get("revision_no")).intValue() != expectedRevision
                || !current.get("snapshot_hash").equals(expectedHash))
            throw error("version_conflict", "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요.");
        apply.accept(current);
        Proposal response = queries.current(project, proposal);
        idempotency.save(project, caller.accountId(), operation, key, input, response);
        // [TODO] 검토 시작·반려 알림과 타임라인은 전진 담당 공통 서비스 계약 확정 후 연결한다.
        return response;
    }

    private static void requireDecidable(Map<String, Object> current, Set<String> allowed, String message) {
        if (!allowed.contains(current.get("status"))) throw error("invalid_transition", message);
    }

    private Snapshot resolve(CallerContext caller, long project, Long proposal, Input input, Stage stage, List<Original> stored) {
        long baseline = requiredId(input.baseBaselineId(), "base_baseline_id");
        requireCurrentBaseline(project, baseline);
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
        for (int index = 0; index < input.documents().size(); index++) {
            DocumentChange doc = input.documents().get(index);
            long document = requiredId(doc.documentId(), "document_id");
            long version = requiredId(doc.baseDocumentVersionId(), "base_document_version_id");
            var base = repository.baseDocument(project, baseline, document);
            if (id(base, "id") != version) throw error("baseline_conflict", "문서의 기준 버전이 기준 설계와 일치하지 않습니다.");
            String after = doc.afterContent();
            String proposedVersion = doc.proposedDocumentVersionId();
            if (proposedVersion == null) {
                // Every document change gets its own DRAFT version and an indexing job, in this transaction, so
                // CHG-A09 can check one rule (index readiness) for upload and direct-text changes alike.
                Integer at = stage.byDocument().get(index);
                if (at == null) throw error("baseline_conflict", "기준 문서가 변경되었습니다. 최신 기준으로 다시 제출해 주세요.");
                Staged file = stage.files().get(at);
                long created = repository.createDraftVersion(document, version, stored.get(at).reference(), file.textHash(),
                        file.text(), file.textHash(), caller.accountId());
                proposedVersion = Long.toString(created);
                after = file.text();
            } else {
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
            // `after` is already normalized like a DOC-A12 upload, so equal text means no change.
            requireChanged((String) base.get("content_text"), after);
            changed = true;
            documents.add(new ResolvedDocument(doc.documentId(), doc.baseDocumentVersionId(), proposedVersion, after,
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
