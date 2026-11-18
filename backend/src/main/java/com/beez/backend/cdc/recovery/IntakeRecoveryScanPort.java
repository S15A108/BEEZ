package com.beez.backend.cdc.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Page through work that may need recovery, implemented by the future #197 adapter.
 *
 * <p>Candidates must come from persistent state: pending past its agreed threshold
 * and running with an expired lease. Implementations must provide bounded,
 * stable pagination and a recurring full sweep so concurrent changes or failed
 * deliveries remain discoverable. A candidate is only a hint; the current row,
 * eligibility, project order and lease must be checked again in the atomic claim.
 * No cursor held only in process memory can by itself guarantee recovery.
 */
@FunctionalInterface
public interface IntakeRecoveryScanPort {
    Page findCandidates(Instant observedAt, int limit, String cursor);

    /** A null nextCursor marks the end of this traversal, not all future sweeps. */
    record Page(List<IntakeRecoveryCandidate> candidates, String nextCursor) {
        public Page {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("A continuation cursor cannot be blank");
            }
        }
    }
}
