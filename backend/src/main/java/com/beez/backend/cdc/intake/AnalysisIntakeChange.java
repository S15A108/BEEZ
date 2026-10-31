package com.beez.backend.cdc.intake;

/**
 * Schema-neutral change supplied by a future transport adapter.
 *
 * <p>The reference identifies an intake for a fresh authoritative lookup. It is
 * opaque here: its representation and its relationship to the physical #197
 * row are determined by the adapter, not by this transport-independent type.
 * The observed status is only a possibly stale hint, never a work reservation.
 */
public record AnalysisIntakeChange(String intakeReference, String operation, String observedStatus) {}
