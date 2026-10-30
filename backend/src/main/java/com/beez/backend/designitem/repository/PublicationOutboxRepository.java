package com.beez.backend.designitem.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists publication events in the same transaction as their immutable baseline. */
@Repository
public class PublicationOutboxRepository {
    private final JdbcClient jdbc;

    public PublicationOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertInitialPublication(long projectId, long baselineId) {
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO publication_outbox(event_id,event_type,event_version,project_id,baseline_id,occurred_at,payload)
                SELECT :event_id, 'DESIGN_PUBLISHED', 1, b.project_id, b.id, b.published_at,
                       jsonb_build_object(
                           'event_id', CAST(:event_id AS text),
                           'event_type', 'DESIGN_PUBLISHED',
                           'event_version', 1,
                           'occurred_at', to_char(b.published_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                           'publication_kind', 'INITIAL',
                           'project_id', CAST(b.project_id AS text),
                           'baseline_id', CAST(b.id AS text),
                           'previous_baseline_id', NULL,
                           'change_proposal_id', NULL,
                           'changed_items', COALESCE((
                               SELECT jsonb_agg(jsonb_build_object(
                                   'design_item_id', CAST(i.design_item_id AS text),
                                   'previous_version_id', NULL,
                                   'published_version_id', CAST(i.design_item_version_id AS text),
                                   'change_type', 'ADDED') ORDER BY i.design_item_id)
                               FROM baseline_item i WHERE i.baseline_id=b.id AND i.project_id=b.project_id
                           ), '[]'::jsonb))
                FROM baseline b WHERE b.id=:baseline_id AND b.project_id=:project_id
                """).param("event_id", eventId).param("project_id", projectId).param("baseline_id", baselineId).update();
        if (inserted != 1) {
            throw new IllegalStateException("published baseline was not found for outbox event");
        }
    }
}
