package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.*;
import static com.beez.backend.changeproposal.validation.ProposalValidator.requiredId;
import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.changeproposal.port.PostPublishAnalysisIntake;
import com.beez.backend.changeproposal.port.ValidationImpactPort;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.changeproposal.repository.PublicationRepository;
import com.beez.backend.changeproposal.validation.ProposalValidator;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.service.RelationStorageService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalPublicationService {
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final ProposalRepository proposals;
    private final PublicationRepository repository;
    private final ProposalQueryService queries;
    private final PublishPlanner planner;
    private final BaselineRepository baselines;
    private final DesignItemRepository items;
    private final DesignCommandService commands;
    private final RelationStorageService relations;
    private final ValidationImpactPort impacts;
    private final PostPublishAnalysisIntake intake;
    private final ProposalValidator validator;
    public ProposalPublicationService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository proposals,
            PublicationRepository repository, ProposalQueryService queries, PublishPlanner planner, BaselineRepository baselines,
            DesignItemRepository items, DesignCommandService commands, RelationStorageService relations,
            ValidationImpactPort impacts, PostPublishAnalysisIntake intake, ProposalValidator validator) {
        this.access=access; this.projects=projects; this.proposals=proposals; this.repository=repository; this.queries=queries;
        this.planner=planner; this.baselines=baselines; this.items=items; this.commands=commands; this.relations=relations;
        this.impacts=impacts; this.intake=intake; this.validator=validator;
    }

    @Transactional
    public Publication approve(CallerContext caller, long project, long proposal, ApproveRequest input, String key) {
        validator.validate(input);
        positive(project,proposal);
        var actor=access.requireHumanLeader(caller,project);
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("proposal_project_missing");
        access.requireLeaderAfterLock(actor);
        var current=proposals.proposal(project,proposal);
        String operation="proposal:approve:"+proposal+":human";
        var replay=commands.replay(project,caller.accountId(),operation,key,input,Publication.class);
        if (replay.isPresent()) return replay.get();
        if (((Number)current.get("revision_no")).intValue()!=input.expectedRevision()
                || !current.get("snapshot_hash").equals(input.expectedSnapshotHash())
                || id(current,"base_baseline_id")!=requiredId(input.expectedBaseBaselineId(),"expected_base_baseline_id"))
            throw conflict("version_conflict");
        if ("PUBLISHED".equals(current.get("status"))) {
            var approval=proposals.publishedApproval(project,proposal)
                    .orElseThrow(() -> new IllegalStateException("Published proposal has no approval"));
            if (!Objects.equals(approval.baselineId(),Objects.toString(current.get("published_baseline_id"),null)))
                throw new IllegalStateException("Published proposal baseline mismatch");
            var result=new Publication(queries.current(project,proposal),repository.response(project,Long.parseLong(approval.baselineId())));
            commands.save(project,caller.accountId(),operation,key,input,result);
            return result;
        }
        var plan=planner.plan(project,proposal,current);
        long author=id(current,"submitted_by");
        String origin=current.get("submitted_token_id")==null ? "HUMAN" : "AI_PROPOSED";
        long baseline=repository.insertBaseline(project,plan.nextVersion(),proposal,caller.accountId());
        var changedDocuments=new HashMap<Long,Long>();
        for (var doc : plan.proposedDocuments()) {
            long after=id(doc,"proposed_document_version_id");
            repository.approveDocument(id(doc,"base_document_version_id"),after,caller.accountId());
            changedDocuments.put(id(doc,"document_id"),after);
        }
        for (var doc : plan.documents()) baselines.insertDocumentRef(baseline,project,id(doc,"document_id"),
                changedDocuments.getOrDefault(id(doc,"document_id"),id(doc,"id")));
        var changedItems=new HashSet<Long>(); var impactItems=new ArrayList<ChangedItem>();
        for (var change : plan.changes()) {
            var item=change.change(); var before=change.before();
            if ("RETIRE".equals(item.operation())) {
                long itemId=Long.parseLong(item.designItemId()); changedItems.add(itemId);
                repository.retire(project,itemId);
                impactItems.add(new ChangedItem(itemId,id(before,"id"),null,"RETIRE"));
                continue;
            }
            long itemId;
            if (before==null) {
                String prefix=switch (item.kind()) {
                    case "REQUIREMENT" -> "REQ"; case "API_CONTRACT" -> "API"; case "DATA_CONTRACT" -> "ENT";
                    case "POLICY" -> "POL"; default -> "CON";
                };
                String displayKey=prefix+"-"+String.format(Locale.ROOT,"%03d",items.nextKeySequence(project,prefix));
                itemId=items.insertItem(project,displayKey,item.kind());
            } else {
                itemId=id(before,"design_item_id"); changedItems.add(itemId);
                repository.supersedeItem(id(before,"id"));
            }
            // Preserve the cited immutable source and locator. A changed document does not prove the old locator
            // identifies the same passage in its new version; source replacement requires an explicit contract.
            Long source=before==null || before.get("source_document_version_id")==null ? null : id(before,"source_document_version_id");
            long version=repository.insertVersion(itemId,change.versionNo(),change.title(),item.schemaVersion(),item.proposedContent(),
                    source,before==null ? null : before.get("source_locator"),origin,author);
            baselines.insertItemRef(baseline,project,itemId,version);
            if (before!=null) impactItems.add(new ChangedItem(itemId,id(before,"id"),version,"UPDATE"));
        }
        for (var item : plan.existingItems()) if (!changedItems.contains(id(item,"design_item_id")))
            baselines.insertItemRef(baseline,project,id(item,"design_item_id"),id(item,"id"));
        if (!impactItems.isEmpty()) relations.apply(caller,project,new RelationChangeRequest(baseline,proposal,
                impactItems.stream().map(ChangedItem::designItemId).toList(),"변경 제안 승인·발행",List.of()));
        var impact=plan.impact();
        impacts.applyPublication(caller,project,new ImpactRequest(baseline,plan.previousBaselineId(),proposal,impact.revision(),
                impact.snapshotHash(),List.copyOf(impactItems),impact.reason()));
        repository.publish(project,proposal,impact.revision(),plan.previousBaselineId(),baseline,caller.accountId());
        intake.accept(project,baseline,proposal);
        var result=new Publication(queries.current(project,proposal),repository.response(project,baseline));
        commands.save(project,caller.accountId(),operation,key,input,result);
        // TODO(180~182 알림·활동): request storage inside this publication transaction (contract 10).
        // Activity: CHANGE_PROPOSAL_PUBLISHED, key change-proposal:{id}:published; duplicate keys are skipped.
        // Non-duplicate storage failures must roll back publication; neither replay branch requests storage again.
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(CallerContext caller, long project, long proposal) {
        positive(project,proposal);
        access.requireHumanLeader(caller,project);
        var plan=planner.plan(project,proposal,proposals.proposal(project,proposal));
        var result=impacts.previewPublication(project,plan.impact());
        // TODO(197 연결): 0 is a compatibility placeholder, not a completed AI evaluation.
        return new Preview(plan.nextVersion(),plan.previewItems(),result.staleValidations(),result.waitingTasks(),plan.unchanged(),
                new Unreviewed(0,repository.openReviewRequests(proposal,plan.impact().revision())));
    }
    private static void positive(long project, long proposal) {
        if (project<1 || proposal<1) throw invalid("프로젝트·제안 ID는 양수여야 합니다.");
    }
}
