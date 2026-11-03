package com.beez.backend.impactanalysis;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.beez.backend.impactanalysis.OwnerRecommendationDtos.*;

/** Produces review candidates; it does not send notifications or change task state. */
@Service
public class OwnerRecommendationService {
    private final DirectOwnerQuery direct;
    private final ImpactRunService runs;
    private final ImpactContextQuery contexts;

    OwnerRecommendationService(DirectOwnerQuery direct, ImpactRunService runs, ImpactContextQuery contexts) {
        this.direct = direct;
        this.runs = runs;
        this.contexts = contexts;
    }

    public record Review(ImpactRunService.Result impact, Result owners) {}

    /** All inputs, freshness checks and recipient membership reads share one DB snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Review forReview(CallerContext caller, long projectId, long proposalId) {
        DirectResult directResult = direct.load(caller, projectId, proposalId);
        // peek does not perform the lease-expiry write inside this read-only transaction.
        ImpactRunService.Result impact = runs.peek(caller, projectId, proposalId);
        Map<String, MutableOwner> owners = new LinkedHashMap<>();
        for (var recommendation : directResult.recommendations()) {
            owners.put(recommendation.memberId(), new MutableOwner(recommendation.area(), recommendation.evidence()));
        }

        boolean eligible = "COMPLETED".equals(impact.analysisStatus()) && impact.isCurrent()
                && impact.reanalysis() == null && impact.revision() == directResult.proposalRevision()
                && directResult.snapshotHash().equals(impact.snapshotHash())
                && "COMPLETE".equals(directResult.directReferenceStatus());
        int withheld = eligible ? 0 : impact.candidates().size();
        if (eligible && !impact.candidates().isEmpty()) {
            var snapshot = contexts.load(caller, projectId, proposalId);
            var request = ImpactRequestAssembler.assemble(snapshot, "owner-recommendation-validation");
            Set<Long> taskIds = new LinkedHashSet<>();
            for (var raw : impact.candidates()) {
                if (raw instanceof Map<?, ?> value) {
                    Long task = positiveId(value.get("task_id"));
                    if (task != null) taskIds.add(task);
                }
            }
            var taskOwners = direct.activeTaskOwners(projectId, taskIds);
            for (var raw : impact.candidates()) {
                if (!(raw instanceof Map<?, ?> candidate)
                        || !(candidate.get("sources") instanceof List<?> sources) || sources.isEmpty()
                        || !ImpactAnalysisCoordinator.candidatesBelongToRequest(List.of(raw), request)) {
                    withheld++;
                    continue;
                }
                Long taskId = positiveId(candidate.get("task_id"));
                var task = taskId == null ? null : taskOwners.get(taskId);
                if (task == null || (candidate.get("member_id") != null
                        && !Long.toString(task.memberId()).equals(candidate.get("member_id")))) {
                    withheld++;
                    continue;
                }
                String member = Long.toString(task.memberId());
                var owner = owners.computeIfAbsent(member, ignored -> new MutableOwner(task.area(), List.of()));
                String itemId = candidate.get("design_item_id") instanceof String id ? id : null;
                String itemVersion = snapshot.approvedItems().stream()
                        .filter(item -> Long.toString(item.itemId()).equals(itemId))
                        .map(item -> Long.toString(item.versionId())).findFirst().orElse(null);
                var evidence = new Evidence("AI_CANDIDATE", (String) candidate.get("reason"),
                        Long.toString(task.taskId()), task.taskKey(), Long.toString(task.planRevisionId()),
                        itemId, itemVersion, null, null, null, sources);
                if (!owner.evidence.contains(evidence)) owner.evidence.add(evidence);
            }
        }

        var recommendations = new ArrayList<Recommendation>();
        owners.forEach((member, owner) -> recommendations.add(new Recommendation(member, owner.area, owner.evidence)));
        return new Review(impact, new Result(directResult.proposalRevision(), directResult.snapshotHash(),
                directResult.directReferenceStatus(), directResult.directReferenceReasons(), impact.analysisStatus(),
                impact.isCurrent(), impact.reanalysis() != null, withheld, recommendations,
                directResult.notificationCandidateMemberIds()));
    }

    /**
     * Handoff for the change domain (#237). Call in the transaction that selects recipients, with
     * the project locked before any task lock. The project lock serializes task, plan and membership
     * changes; the common notification service
     * must still recheck membership when it receives the request.
     * The caller decides event-specific exclusions and converts member IDs to account IDs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DirectResult forNotification(CallerContext caller, long projectId, long proposalId,
                                        int expectedRevision, String expectedSnapshotHash) {
        direct.lockProject(caller, projectId);
        DirectResult result = direct.load(caller, projectId, proposalId);
        if (expectedRevision != result.proposalRevision() || expectedSnapshotHash == null
                || !expectedSnapshotHash.equals(result.snapshotHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "version_conflict", "변경 제안 리비전을 다시 확인해야 합니다");
        }
        return result;
    }

    private static Long positiveId(Object value) {
        if (!(value instanceof String text)) return null;
        try { long id = Long.parseLong(text); return id > 0 ? id : null; }
        catch (NumberFormatException malformed) { return null; }
    }

    private static class MutableOwner {
        final String area;
        final List<Evidence> evidence;
        MutableOwner(String area, List<Evidence> evidence) {
            this.area = area;
            this.evidence = new ArrayList<>(evidence);
        }
    }
}
