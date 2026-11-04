package com.beez.backend.impactanalysis.intake;

/** Shared predecessor rule for an intake row aliased as {@code a}. */
public final class PublishedAnalysisOrderingSql {
    private PublishedAnalysisOrderingSql() { }

    /** Mirrors the publication worker's rule, including the v1 and historical backfill exceptions. */
    public static final String PREDECESSORS_READY = """
            NOT EXISTS (SELECT 1 FROM baseline current
              CROSS JOIN LATERAL generate_series(1,current.version_no-1) required(version_no)
              WHERE current.id=a.published_baseline_id AND current.project_id=a.project_id
                AND NOT EXISTS (SELECT 1 FROM baseline b WHERE b.project_id=a.project_id AND b.version_no=required.version_no))
            AND NOT EXISTS (SELECT 1 FROM baseline eb
              JOIN baseline current ON current.id=a.published_baseline_id AND current.project_id=a.project_id
              LEFT JOIN published_change_analysis earlier ON earlier.published_baseline_id=eb.id AND earlier.project_id=eb.project_id
              WHERE eb.project_id=a.project_id AND eb.version_no<current.version_no
                AND (earlier.id IS NULL OR NOT COALESCE(earlier.status='COMPLETED' OR
                  (earlier.status='NOT_EVALUATED' AND
                    ((eb.version_no=1 AND earlier.change_proposal_id IS NULL) OR earlier.error_code='published_before_intake')), false)))
            """;
}
