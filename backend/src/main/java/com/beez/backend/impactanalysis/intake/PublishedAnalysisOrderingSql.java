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
     * Shared by the publication worker and the blocked-project metric.
     *
     * <p>앞 버전은 빠졌거나(버전·접수행 누락) 아직 PENDING·RUNNING일 때만 기다린다. FAILED·NOT_EVALUATED로 끝난 앞 버전은
     * 다시 처리해도 완료될 수 없다 — 발행 후 분석은 그 baseline 이 프로젝트의 현재 baseline 일 때만 돌고
     * ({@code ImpactContextQuery}, 아니면 {@code baseline_changed}), 이 접수가 있다는 것은 더 뒤의 baseline 이 이미
     * 발행됐다는 뜻이기 때문이다. 그런 앞 버전을 기다리면 그 프로젝트의 발행 후 분석이 영구히 멈춘다 (S15P31A108-408).
     * 끝난 앞 버전의 실패·미평가는 지표·알림(FAILED, 확인 필요 미평가)에 그대로 남는다.
     */
    public static final String PREDECESSORS_READY = """
            NOT EXISTS (SELECT 1 FROM baseline current
              CROSS JOIN LATERAL generate_series(1,current.version_no-1) required(version_no)
              WHERE current.id=a.published_baseline_id AND current.project_id=a.project_id
                AND NOT EXISTS (SELECT 1 FROM baseline b WHERE b.project_id=a.project_id AND b.version_no=required.version_no))
            AND NOT EXISTS (SELECT 1 FROM baseline eb
              JOIN baseline current ON current.id=a.published_baseline_id AND current.project_id=a.project_id
              LEFT JOIN published_change_analysis earlier ON earlier.published_baseline_id=eb.id AND earlier.project_id=eb.project_id
              WHERE eb.project_id=a.project_id AND eb.version_no<current.version_no
                AND (earlier.id IS NULL OR earlier.status IN ('PENDING','RUNNING')))
            """;
}
