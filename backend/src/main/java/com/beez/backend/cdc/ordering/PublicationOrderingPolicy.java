package com.beez.backend.cdc.ordering;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Determines whether a single project's PENDING intake may be considered for
 * an ordered claim from a complete, consistent snapshot of published versions.
 *
 * <p>This is a pure decision helper, not a lock or a claim. The #197 adapter
 * must read project-scoped authoritative current rows and perform the final
 * conditional claim under the same transaction/lock or equivalent concurrency
 * guarantee. An old Kafka event or an independently read snapshot is not proof.
 */
public final class PublicationOrderingPolicy {
    private PublicationOrderingPolicy() {}

    public enum State { PENDING, RUNNING, COMPLETED, FAILED, NOT_EVALUATED, UNKNOWN }

    /** Evidence verified by the adapter against the actual #197 contract. */
    public enum SkipEvidence { NONE, INITIAL_BASELINE, PRE_197_BACKFILL }

    public enum Outcome { READY_FOR_ATOMIC_CLAIM, WAITING_FOR_PREDECESSOR, REQUIRES_RECONCILIATION, NOT_PENDING }

    public record PublishedVersion(long projectId, int version, State state, SkipEvidence skipEvidence) {
        public PublishedVersion {
            if (projectId <= 0 || version <= 0) {
                throw new IllegalArgumentException("Project and version must be positive");
            }
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(skipEvidence, "skipEvidence");
            if (state != State.NOT_EVALUATED && skipEvidence != SkipEvidence.NONE) {
                throw new IllegalArgumentException("Skip evidence requires NOT_EVALUATED");
            }
            if (skipEvidence == SkipEvidence.INITIAL_BASELINE && version != 1) {
                throw new IllegalArgumentException("Only version 1 can be the initial baseline");
            }
        }
    }

    /** blockingVersion identifies the first relevant version when available. */
    public record Decision(Outcome outcome, Integer blockingVersion) {}

    /**
     * @param projectId expected project whose versions are checked
     * @param targetVersion version of the proposed work (versions start at 1)
     * @param authoritativeSnapshot true only if the adapter guarantees a complete,
     *     current, project-scoped view; final claim must still be atomic with it
     * @param versions version records at or before targetVersion
     * @return READY only when target is PENDING and every earlier published
     *     version is complete or explicitly verified as v1/pre-197 skipped
     */
    public static Decision evaluate(long projectId, int targetVersion, boolean authoritativeSnapshot,
                                    List<PublishedVersion> versions) {
        if (projectId <= 0 || targetVersion <= 0) {
            throw new IllegalArgumentException("Project and target version must be positive");
        }
        Objects.requireNonNull(versions, "versions");
        if (!authoritativeSnapshot) return new Decision(Outcome.REQUIRES_RECONCILIATION, null);

        var byVersion = new TreeMap<Integer, PublishedVersion>();
        for (var item : versions) {
            Objects.requireNonNull(item, "version item");
            if (item.projectId() != projectId) {
                throw new IllegalArgumentException("Snapshot contains a different project");
            }
            if (item.version() > targetVersion) {
                throw new IllegalArgumentException("Snapshot contains a version beyond target");
            }
            if (byVersion.putIfAbsent(item.version(), item) != null) {
                return new Decision(Outcome.REQUIRES_RECONCILIATION, item.version());
            }
        }
        var target = byVersion.get(targetVersion);
        if (target == null) return new Decision(Outcome.REQUIRES_RECONCILIATION, targetVersion);
        if (target.state() == State.UNKNOWN || (target.state() == State.NOT_EVALUATED
                && target.skipEvidence() == SkipEvidence.NONE)) {
            return new Decision(Outcome.REQUIRES_RECONCILIATION, targetVersion);
        }
        if (target.state() != State.PENDING) return new Decision(Outcome.NOT_PENDING, targetVersion);
        // The initial baseline is not a post-publication impact-analysis job.
        // An unexpected PENDING v1 must be reconciled, never automatically claimed.
        if (targetVersion == 1) return new Decision(Outcome.REQUIRES_RECONCILIATION, targetVersion);

        // Iterating the supplied rows rather than every possible version avoids
        // a pathological loop for an invalid/maliciously huge targetVersion.
        long expected = 1;
        for (var entry : byVersion.entrySet()) {
            if (entry.getKey() != expected) {
                return new Decision(Outcome.REQUIRES_RECONCILIATION, Math.toIntExact(expected));
            }
            if (entry.getKey() == targetVersion) break;
            var previous = entry.getValue();
            if (previous.state() == State.NOT_EVALUATED) {
                if (previous.skipEvidence() == SkipEvidence.NONE) {
                    return new Decision(Outcome.REQUIRES_RECONCILIATION, previous.version());
                }
            } else if (previous.state() == State.UNKNOWN) {
                return new Decision(Outcome.REQUIRES_RECONCILIATION, previous.version());
            } else if (previous.state() != State.COMPLETED) {
                return new Decision(Outcome.WAITING_FOR_PREDECESSOR, previous.version());
            }
            expected++;
        }
        return new Decision(Outcome.READY_FOR_ATOMIC_CLAIM, null);
    }
}
