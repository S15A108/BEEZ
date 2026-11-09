package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.*;
import static com.beez.backend.changeproposal.validation.ProposalValidator.requiredId;
import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
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
import com.beez.backend.notification.event.ChangeNotifications;
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
    private final ImpactApprovalGate gate;
    private final ChangeNotifications notifications;
    private final ActivityRecorder activities;
    public ProposalPublicationService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository proposals,
            PublicationRepository repository, ProposalQueryService queries, PublishPlanner planner, BaselineRepository baselines,
            DesignItemRepository items, DesignCommandService commands, RelationStorageService relations,
            ValidationImpactPort impacts, PostPublishAnalysisIntake intake, ProposalValidator validator,
            ImpactApprovalGate gate, ChangeNotifications notifications, ActivityRecorder activities) {
        this.notifications=notifications;
        this.activities=activities;
        this.access=access; this.projects=projects; this.proposals=proposals; this.repository=repository; this.queries=queries;
        this.planner=planner; this.baselines=baselines; this.items=items; this.commands=commands; this.relations=relations;
        this.impacts=impacts; this.intake=intake; this.validator=validator; this.gate=gate;
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
        // Last gate before any write. Same project lock as the analysis save, so the verdict cannot change before publish.
        String overrideReason=gate.enforce(gate.evaluate(caller,project,proposal),
                input.impactOverrideReason()==null ? null : input.impactOverrideReason().strip());
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
        var impactResult=impacts.applyPublication(caller,project,new ImpactRequest(baseline,plan.previousBaselineId(),proposal,impact.revision(),
                impact.snapshotHash(),List.copyOf(impactItems),impact.reason()));
        repository.publish(project,proposal,impact.revision(),plan.previousBaselineId(),baseline,caller.accountId(),overrideReason);
        intake.accept(project,baseline,proposal);
        var result=new Publication(queries.current(project,proposal),repository.response(project,baseline));
        commands.save(project,caller.accountId(),operation,key,input,result);
        // Notifications are stored in this publication transaction (contract 10); neither replay branch above reaches here.
        // The proposal is PUBLISHED and the new baseline is visible now, which forNotification requires. A non-duplicate
        // storage failure rolls back the whole publication.
        notifications.published(caller,project,proposal,id(current,"proposer_account_id"),input.expectedRevision(),
                input.expectedSnapshotHash(),affectedTasks(impactResult));
        // Activities share this transaction too. APPROVED is stored before PUBLISHED so the id-descending timeline shows
        // the publication on top. Replay and already-published returns above never reach here.
        activities.record(caller,ActivityEvent.of("change-proposal:"+proposal+":approved",project,"CHANGE_PROPOSAL_APPROVED",
                ActivityTargetType.CHANGE_PROPOSAL,proposal));
        activities.record(caller,ActivityEvent.of("change-proposal:"+proposal+":published",project,"CHANGE_PROPOSAL_PUBLISHED",
                ActivityTargetType.CHANGE_PROPOSAL,proposal).withBaselineId(baseline));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(CallerContext caller, long project, long proposal) {
        positive(project,proposal);
        access.requireHumanLeader(caller,project);
        var plan=planner.plan(project,proposal,proposals.proposal(project,proposal));
        var result=impacts.previewPublication(project,plan.impact());
        var gateDecision=gate.peek(caller,project,proposal);
        // Candidates are counted only from a trusted analysis; otherwise null, never 0.
        Integer aiCandidates=gateDecision.kind()==ImpactApprovalGate.Kind.TRUSTED ? gateDecision.candidateCount() : null;
        return new Preview(plan.nextVersion(),plan.previewItems(),result.staleValidations(),result.waitingTasks(),plan.unchanged(),
                new Unreviewed(aiCandidates,repository.openReviewRequests(proposal,plan.impact().revision()),
                        new AiAnalysis(gateDecision.status(),gateDecision.isCurrent(),gateDecision.errorCode(),gateDecision.reanalysis()),
                        gateDecision.needsReason()));
    }
    /** Tasks whose validation went STALE or that now wait for revalidation; the notification layer filters status and assignee. */
    private static Set<Long> affectedTasks(ImpactResult result) {
        var ids=new LinkedHashSet<Long>();
        for (var task : result.waitingTasks()) ids.add(Long.parseLong(task.taskId()));
        for (var validation : result.staleValidations()) if (validation.taskId()!=null) ids.add(Long.parseLong(validation.taskId()));
        return ids;
    }
    private static void positive(long project, long proposal) {
        if (project<1 || proposal<1) throw invalid("프로젝트·제안 ID는 양수여야 합니다.");
    }
}
