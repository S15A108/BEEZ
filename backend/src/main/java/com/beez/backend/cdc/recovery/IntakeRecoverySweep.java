package com.beez.backend.cdc.recovery;

import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;

/**
 * Processes one bounded page of recovery hints; no scheduler or persistence.
 *
 * <p>Several processes may scan the same row. Safety depends on the actual
 * #197 adapter enforcing atomic claim with project ordering, attempt/lease
 * fencing and result storage in the same transaction. A claim is not enough:
 * a guaranteed durable rescan must revisit pending work and expired leases.
 * External LLM side effects may still repeat. This class does not provide these
 * guarantees and must not be enabled as a standalone recovery mechanism.
 */
public final class IntakeRecoverySweep {
    public static final int MAX_PAGE_SIZE = 500;

    private final IntakeRecoveryScanPort scan;
    private final AnalysisIntakeCheckPort intakeCheck;

    public IntakeRecoverySweep(IntakeRecoveryScanPort scan, AnalysisIntakeCheckPort intakeCheck) {
        this.scan = Objects.requireNonNull(scan, "scan");
        this.intakeCheck = Objects.requireNonNull(intakeCheck, "intakeCheck");
    }

    /**
     * Attempt each distinct reference at most once per page; another instance or
     * sweep may safely observe it again only if the adapter honors its contract.
     * A failed row remains persistent and must be revisited by a later full
     * sweep; this result is not a durable checkpoint or an acknowledgement.
     * Exceptions are counted without exposing reference IDs or error contents.
     * Scan/invalid page errors fail the whole call before any row is dispatched.
     */
    public Result runPage(Instant observedAt, int limit, String cursor) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (limit < 1 || limit > MAX_PAGE_SIZE || (cursor != null && cursor.isBlank())) {
            throw new IllegalArgumentException("Invalid recovery scan bounds");
        }
        var page = Objects.requireNonNull(scan.findCandidates(observedAt, limit, cursor), "scan page");
        if (page.candidates().size() > limit) {
            throw new IllegalStateException("Recovery scan exceeded the requested page limit");
        }
        if (cursor != null && cursor.equals(page.nextCursor())) {
            throw new IllegalStateException("Recovery scan cursor did not advance");
        }
        var seen = new HashSet<String>();
        int considered = 0;
        int failed = 0;
        for (var candidate : page.candidates()) {
            if (!seen.add(candidate.intakeReference())) continue;
            considered++;
            try {
                intakeCheck.ensureSafeToAcknowledge(candidate.intakeReference());
            } catch (RuntimeException failure) {
                // Row remains eligible for later durable rescan. Do not log data.
                failed++;
            }
        }
        return new Result(considered, failed, page.nextCursor());
    }

    public record Result(int considered, int failed, String nextCursor) {}
}
