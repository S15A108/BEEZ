package com.beez.backend.cdc.intake;

import java.util.Objects;

/** Routes normalized events; it neither parses #197 rows nor executes analysis. */
public final class AnalysisIntakeSignalHandler {
    public enum Result { IGNORED, CHECK_ACCEPTED }

    private final AnalysisIntakeCheckPort intakeCheck;

    public AnalysisIntakeSignalHandler(AnalysisIntakeCheckPort intakeCheck) {
        this.intakeCheck = Objects.requireNonNull(intakeCheck, "intakeCheck");
    }

    /**
     * A PENDING insert/snapshot/update is a wake-up hint. A returned
     * CHECK_ACCEPTED means only that the adapter fulfilled the durability
     * contract; it does not mean analysis has started or finished. Adapter and
     * validation failures are intentionally propagated to prevent silent loss.
     */
    public Result handle(AnalysisIntakeChange change) {
        Objects.requireNonNull(change, "change");
        var decision = AnalysisIntakeEventPolicy.decide(change.operation(), change.observedStatus());
        // Every supported row change must identify its source row, including a
        // status that would otherwise be ignored. A tombstone is handled via
        // the separate method; malformed rows must never look like tombstones.
        String reference = change.intakeReference();
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Intake reference is required");
        }
        if (decision == AnalysisIntakeEventPolicy.Decision.IGNORE) return Result.IGNORED;
        intakeCheck.ensureSafeToAcknowledge(reference);
        return Result.CHECK_ACCEPTED;
    }

    /** The transport adapter must distinguish a true tombstone from parse failure. */
    public Result handleTombstone() {
        return Result.IGNORED;
    }
}
