package com.beez.backend.impactanalysis.intake;

/** Shared predecessor rule for an intake row aliased as {@code a}. */
public final class PublishedAnalysisOrderingSql {
    private PublishedAnalysisOrderingSql() { }

    /** SQL aliases must be fixed identifiers from the calling query, never user input. */
    public static String expectedNotEvaluated(String baselineAlias, String intakeAlias) {
        return ("((%1$s.version_no=1 AND %2$s.change_proposal_id IS NULL) "
                + "OR %2$s.error_code='published_before_intake')").formatted(baselineAlias, intakeAlias);
    }

    /**
     * Document changes are still unevaluated and require review, but cannot be
     * retried successfully until document analysis is supported. They must not
     * prevent independent later publications from being analyzed.
     */
    private static String nonBlockingNotEvaluated(String baselineAlias, String intakeAlias) {
        return "(%s OR %s.error_code='document_change_unavailable')"
                .formatted(expectedNotEvaluated(baselineAlias, intakeAlias), intakeAlias);
    }

    /** Shared by the publication worker and the blocked-project metric. */
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
                  (earlier.status='NOT_EVALUATED' AND %s), false)))
            """.formatted(nonBlockingNotEvaluated("eb", "earlier"));
}
