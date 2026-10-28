package com.beez.backend.impactanalysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic candidate discovery; a path is evidence of a relationship, not proof of impact. */
public final class ImpactCandidateFinder {
    private ImpactCandidateFinder() {}

    public enum Kind { DIRECT_REFERENCE, INDIRECT_DEPENDENCY }
    public enum Coverage { UNKNOWN }

    /** A changed item is absent for CREATE and cannot be linked via existing item references. */
    public record Change(long changeItemId, Long itemId, Long beforeVersionId, String operation) {}

    /** fromItem depends on toItem. The event binds the relationship to both versions. */
    public record Dependency(long fromItemId, long fromVersionId, long toItemId,
                             long toVersionId, long confirmationEventId) {}

    public record PlanReference(long taskId, Long assigneeMemberId, long planRevisionId,
                                long itemId, long itemVersionId, String relation) {}

    /** Preserve each path, even if multiple paths lead to the same task. */
    public record Path(Kind kind, long changeItemId, long changedItemId, long changedVersionId,
                       long taskId, Long assigneeMemberId, long planRevisionId,
                       long referencedItemId, long referencedVersionId,
                       Long requiredReferenceVersionId, String referenceRelation,
                       Long confirmationEventId, boolean staleReference) {}

    public record Result(List<Path> paths, Coverage indirectCoverage, boolean semanticAnalysisRequired) {
        public Result {
            paths = List.copyOf(paths);
        }
    }

    /**
     * The caller supplies only current-baseline confirmed relationships and current plan references
     * from the same project. Coverage is UNKNOWN even when no paths are found: the input graph may
     * have unconfirmed or missing links. Only one dependency edge is followed in reverse.
     */
    public static Result find(List<Change> changes, List<Dependency> dependencies,
                              List<PlanReference> references) {
        Set<Path> result = new LinkedHashSet<>();
        boolean semanticRequired = false;
        for (Change change : changes) {
            if (change.itemId() == null || change.beforeVersionId() == null) {
                semanticRequired = true;
                continue;
            }
            for (PlanReference reference : references) {
                if (reference.itemId() == change.itemId()) {
                    result.add(new Path(Kind.DIRECT_REFERENCE, change.changeItemId(), change.itemId(),
                            change.beforeVersionId(), reference.taskId(), reference.assigneeMemberId(),
                            reference.planRevisionId(), reference.itemId(), reference.itemVersionId(),
                            change.beforeVersionId(), reference.relation(), null,
                            reference.itemVersionId() != change.beforeVersionId()));
                }
                for (Dependency dependency : dependencies) {
                    if (dependency.toItemId() == change.itemId()
                            && dependency.toVersionId() == change.beforeVersionId()
                            && reference.itemId() == dependency.fromItemId()) {
                        result.add(new Path(Kind.INDIRECT_DEPENDENCY, change.changeItemId(), change.itemId(),
                                change.beforeVersionId(), reference.taskId(), reference.assigneeMemberId(),
                                reference.planRevisionId(), reference.itemId(), reference.itemVersionId(),
                                dependency.fromVersionId(), reference.relation(), dependency.confirmationEventId(),
                                reference.itemVersionId() != dependency.fromVersionId()));
                    }
                }
            }
        }
        return new Result(new ArrayList<>(result), Coverage.UNKNOWN, semanticRequired);
    }
}
