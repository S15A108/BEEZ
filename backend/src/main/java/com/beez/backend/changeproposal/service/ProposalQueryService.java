package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.*;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.diff.LineDiff;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProposalQueryService {
    private final ProjectAccessChecker access;
    private final ProposalRepository repository;
    public ProposalQueryService(ProjectAccessChecker access, ProposalRepository repository) {
        this.access=access; this.repository=repository;
    }

    public Page list(CallerContext caller, long project, String status, String group, String cursor, int limit) {
        access.requireMember(caller, project); size(limit);
        if (status != null && !Set.of("PROPOSED","IN_REVIEW","APPROVED","PUBLISHED","REJECTED","WITHDRAWN").contains(status))
            throw invalid("지원하지 않는 제안 상태입니다.");
        if (group != null && !Set.of("IN_PROGRESS","PUBLISHED","REJECTED").contains(group))
            throw invalid("지원하지 않는 제안 목록 탭입니다.");
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
                if (parts.length != 4 || Long.parseLong(parts[0]) != project || !parts[1].equals(Objects.toString(status, ""))
                        || !parts[2].equals(Objects.toString(group, "")))
                    throw new IllegalArgumentException();
                before = Long.parseLong(parts[3]);
                if (before < 1) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw invalid("현재 프로젝트·상태·탭에 맞는 목록 커서가 필요합니다."); }
        }
        var all = repository.list(project, status, group, before, limit + 1);
        boolean more = all.size() > limit;
        var selected = List.copyOf(all.subList(0, Math.min(limit, all.size())));
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (project + ":" + Objects.toString(status, "") + ":" + Objects.toString(group, "") + ":" + selected.getLast().id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(selected, more, next);
    }

    /** Called inside the command transaction; authorization and the project lock are already held. */
    Proposal current(long project, long proposal) { return view(repository.proposal(project, proposal), null); }

    public Detail detail(CallerContext caller, long project, long proposal, Integer revision) {
        access.requireMember(caller, project);
        var row = repository.proposal(project, proposal);
        Map<String, Object> historical = null;
        if (revision != null) {
            if (revision < 1) throw invalid("리비전 번호는 양수여야 합니다.");
            historical = repository.revision(project, proposal, revision);
        }
        Proposal value = view(row, historical);
        long baseline = Long.parseLong(value.baseBaselineId());
        Long current = value.currentBaselineId() == null ? null : Long.valueOf(value.currentBaselineId());
        var comparisons = new ArrayList<Comparison>();
        var itemIds = new HashSet<Long>(); var documentIds = new HashSet<Long>();
        for (Item item : value.items()) if (item.designItemId() != null) itemIds.add(Long.valueOf(item.designItemId()));
        var bases = repository.baseItems(project, baseline, itemIds);
        var currents = current == null ? Map.<Long, Map<String, Object>>of() : repository.baseItems(project, current, itemIds);
        for (Item item : value.items()) {
            Map<String, Object> before = null, now = null;
            if (item.designItemId() != null) {
                long id = Long.parseLong(item.designItemId()); itemIds.add(id);
                if (!bases.containsKey(id)) throw ProposalCommandService.error("baseline_conflict", "제안의 기준 항목을 확인할 수 없습니다.");
                before = object(bases.get(id).get("content"));
                now = currents.containsKey(id) ? object(currents.get(id).get("content")) : null;
            }
            Map<String, Object> after = "RETIRE".equals(item.operation()) ? null : item.proposedContent();
            comparisons.add(new Comparison("DESIGN_ITEM", item.designItemId(), "STRUCTURED", before, now, after,
                    LineDiff.compare(before == null ? "" : canonical(before), after == null ? "" : canonical(after)),
                    !Objects.equals(before, now)));
        }
        for (ResolvedDocument doc : value.documents()) {
            long id = Long.parseLong(doc.documentId()); documentIds.add(id);
            String before = Objects.toString(repository.baseDocument(project, baseline, id).get("content_text"), "");
            String now = current == null ? null : currentDocument(project, current, id);
            comparisons.add(new Comparison("DOCUMENT", doc.documentId(), "TEXT", before, now, doc.afterContent(),
                    LineDiff.compare(before, doc.afterContent()), !Objects.equals(before, now)));
        }
        var candidates = repository.candidates(project, baseline, itemIds, documentIds, 501);
        return new Detail(value, List.copyOf(comparisons), List.copyOf(candidates.subList(0, Math.min(500, candidates.size()))),
                candidates.size() > 500);
    }

    public RevisionPage revisions(CallerContext caller, long project, long proposal, Integer before, int limit) {
        access.requireMember(caller, project); size(limit); repository.proposal(project, proposal);
        if (before != null && before < 1) throw invalid("이력 커서는 양수여야 합니다.");
        var all = repository.revisions(project, proposal, before == null ? Integer.MAX_VALUE : before, limit + 1);
        boolean more = all.size() > limit;
        var selected = List.copyOf(all.subList(0, Math.min(limit, all.size())));
        return new RevisionPage(selected, more, more ? selected.getLast().revision() : null);
    }

    public ApprovalRecord approval(CallerContext caller, long project, long proposal) {
        access.requireMember(caller, project);
        repository.proposal(project, proposal);
        return repository.publishedApproval(project, proposal)
                .orElseThrow(() -> AccessErrors.notFound("proposal_approval_missing"));
    }

    private Proposal view(Map<String, Object> row, Map<String, Object> historical) {
        var revision = historical == null ? row : historical;
        Snapshot snapshot = JSON.readValue(revision.get("snapshot").toString(), Snapshot.class);
        Input input = snapshot.input();
        String status = historical != null && !Objects.equals(historical.get("revision_no"),row.get("revision_no")) && historical.get("decision") != null
                ? historical.get("decision").toString() : row.get("status").toString();
        return new Proposal(row.get("id").toString(), row.get("key").toString(), row.get("project_id").toString(),
                input.baseBaselineId(), input.title(), input.reason(), input.taskId(), input.validationId(), input.items(), snapshot.documents(),
                status, row.get("proposer_account_id").toString(), revision.get("submitted_token_id") == null ? "HUMAN" : "AGENT",
                nullable(row.get("current_baseline_id")), !Objects.equals(input.baseBaselineId(), nullable(row.get("current_baseline_id"))),
                nullable(historical == null ? row.get("published_baseline_id") : historical.get("result_baseline_id")),
                ((Number) revision.get("revision_no")).intValue(), revision.get("snapshot_hash").toString(),
                time(row.get("created_at")), time(historical == null ? row.get("updated_at") : historical.get("created_at")),
                "REJECTED".equals(status) ? nullable((historical == null ? row : historical).get("rejection_reason")) : null,
                nullable(row.get("previous_proposal_id")),
                "WITHDRAWN".equals(status) ? nullable(row.get("withdrawn_by")) : null,
                "WITHDRAWN".equals(status) ? time(row.get("withdrawn_at")) : null);
    }
    private String currentDocument(long project, long baseline, long id) {
        try { return Objects.toString(repository.baseDocument(project, baseline, id).get("content_text"), ""); }
        catch (ApiException e) { if (e.getStatus().value() == 404) return null; throw e; }
    }
    private static String nullable(Object value) { return value == null ? null : value.toString(); }
    private static void size(int size) { if (size < 1 || size > 100) throw invalid("페이지 크기는 1~100입니다."); }
}
