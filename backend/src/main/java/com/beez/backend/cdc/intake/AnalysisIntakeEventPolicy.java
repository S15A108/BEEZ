package com.beez.backend.cdc.intake;

import java.util.Set;

/**
 * A transport-independent wake-up hint for the agreed analysis intake contract.
 * This class does not parse a physical table, read the DB, or start analysis.
 * An event can be stale or replayed: a worker must re-read and atomically claim
 * the current PENDING intake before performing work. A durable scan/recovery
 * path is also required; Kafka delivery alone is not a work reservation.
 */
public final class AnalysisIntakeEventPolicy {
    public enum Decision { CHECK_CURRENT_INTAKE, IGNORE }

    private static final Set<String> KNOWN_STATUSES = Set.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "NOT_EVALUATED");
    private static final Set<String> ROW_OPERATIONS = Set.of("c", "r", "u");

    private AnalysisIntakeEventPolicy() {}

    /**
     * Uses Debezium operation codes: c=insert, r=snapshot, u=update, d=delete.
     * Delete events fail closed until the #197 row-retention policy is agreed.
     * A pending update is merely a wake-up hint, not authorization to analyze;
     * the contract for manual reanalysis and status transitions is not decided.
     * Transport tombstones are to be handled separately by the adapter.
     * Unknown operations/statuses fail closed so they cannot be silently lost.
     */
    public static Decision decide(String operation, String afterStatus) {
        if ("d".equals(operation)) {
            throw new IllegalArgumentException("Intake deletion policy is not defined");
        }
        if (operation == null || !ROW_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unsupported intake event operation");
        }
        if (afterStatus == null || !KNOWN_STATUSES.contains(afterStatus)) {
            throw new IllegalArgumentException("Unknown intake event status");
        }
        return "PENDING".equals(afterStatus) ? Decision.CHECK_CURRENT_INTAKE : Decision.IGNORE;
    }
}
