package com.beez.backend.cdc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commit the receipt before the listener returns and Kafka commits its offset. */
@Service
public class PublicationInboxService {
    private final JdbcClient jdbc;
    private final PublicationProcessingService processing;

    public PublicationInboxService(JdbcClient jdbc, PublicationProcessingService processing) {
        this.jdbc = jdbc;
        this.processing = processing;
    }

    @Transactional
    public void accept(PublicationEvent event) {
        if ("INITIAL".equals(event.publicationKind())) {
            boolean initial = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM baseline
                      WHERE id=:baseline AND project_id=:project
                        AND version_no=1 AND change_proposal_id IS NULL)
                    """)
                    .param("baseline", event.baselineId()).param("project", event.projectId())
                    .query(Boolean.class).single();
            if (!initial) throw new CdcEventException("Initial publication does not match baseline");
        }
        int inserted = jdbc.sql("""
                INSERT INTO cdc_publication_inbox(event_id,project_id,baseline_id,publication_kind,status,payload)
                VALUES(:id,:project,:baseline,:kind,:status,CAST(:payload AS jsonb))
                ON CONFLICT(event_id) DO NOTHING
                """)
                .param("id", event.eventId()).param("project", event.projectId()).param("baseline", event.baselineId())
                .param("kind", event.publicationKind()).param("status", "PENDING")
                .param("payload", event.payloadJson()).update();
        if (inserted == 0) {
            boolean same = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM cdc_publication_inbox
                      WHERE event_id=:id AND project_id=:project AND baseline_id=:baseline
                        AND publication_kind=:kind AND payload=CAST(:payload AS jsonb))
                    """)
                    .param("id", event.eventId()).param("project", event.projectId()).param("baseline", event.baselineId())
                    .param("kind", event.publicationKind()).param("payload", event.payloadJson()).query(Boolean.class).single();
            if (!same) throw new CdcEventException("Event id was reused with different contents");
        }
        // Persist receipt and downstream work together, before Kafka can acknowledge the offset.
        processing.enqueue(event.projectId(), event.baselineId(), event.eventId());
    }
}
