package com.beez.backend.cdc.recovery;

import java.util.Objects;

/** A stale observation, not evidence that the intake is currently claimable. */
public record IntakeRecoveryCandidate(String intakeReference, Kind kind) {
    public enum Kind { PENDING, EXPIRED_RUNNING }

    public IntakeRecoveryCandidate {
        if (intakeReference == null || intakeReference.isBlank()) {
            throw new IllegalArgumentException("Recovery intake reference is required");
        }
        Objects.requireNonNull(kind, "kind");
    }
}
