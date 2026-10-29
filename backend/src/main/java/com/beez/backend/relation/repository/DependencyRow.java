package com.beez.backend.relation.repository;

import java.time.Instant;

/** design_item_dependency 한 줄 */
public record DependencyRow(Long fromItemId, Long toItemId, String origin, String status, String reason,
                            Long confirmedBy, Instant confirmedAt) {
}
