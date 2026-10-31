package com.beez.backend.cdc.intake;

/** Boundary implemented by the eventual #197-aware, durable intake adapter. */
@FunctionalInterface
public interface AnalysisIntakeCheckPort {
    /**
     * Resolve the reference against authoritative current state and return only
     * when the delivery can safely be acknowledged: either the current intake
     * no longer needs processing, or the pending/running work remains durably
     * discoverable and a recovery mechanism is guaranteed to revisit it after
     * process failure and lease expiry. An atomic claim alone is insufficient.
     *
     * <p>Merely observing a PENDING row, or placing an in-memory notification,
     * does not satisfy this contract, even after an atomic claim, without guaranteed
     * recovery of stranded work. Duplicate deliveries and stale snapshots
     * are expected. The implementation owns transaction boundaries, atomic
     * claim/fencing and durable recovery. Failures must throw so the caller can
     * retry delivery. This interface does not itself provide these guarantees.
     */
    void ensureSafeToAcknowledge(String intakeReference);
}
