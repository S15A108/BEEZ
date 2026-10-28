package com.beez.backend.ontology;

import java.time.Instant;

/** A human-confirmed directed dependency at one specific baseline, with auditable evidence. */
public record ConfirmedDependency(
        long eventId,
        long baselineId,
        long fromItemId,
        long fromVersionId,
        long toItemId,
        long toVersionId,
        long confirmedBy,
        Instant confirmedAt,
        String reason) {
}
