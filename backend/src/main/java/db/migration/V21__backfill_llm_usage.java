package db.migration;

import com.beez.backend.usage.repository.LlmUsageRecorder;
import com.beez.backend.usage.repository.LlmUsageRecorder.Row;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * S15P31A108-169 비용 조회(MON-A02)는 공통 사용량 표(llm_usage)만 읽는다. 이 표에 적지 않고 각자 실행 기록에만 남긴 과거 사용량을 옮긴다.
 * <ol>
 *   <li>검증 실행(validation_run.usage): -159 부터 남겼고 llm_usage 기록은 -168 부터라 그 사이 실행</li>
 *   <li>승인 전 변경 영향 분석(change_impact_run.usage): -169 전까지 llm_usage 에 적지 않음</li>
 *   <li>발행 뒤 변경 영향 분석 시도(published_analysis_attempt.usage): 같은 이유. 최종 결과의 사용량도 시도 행에 있다</li>
 * </ol>
 * 문서 분석·색인·검토는 처음부터 llm_usage 에 함께 적었다.
 *
 * <p>SQL 이 아니라 Java 로 쓰는 이유: 거르기 규칙(숫자 읽기·빈 글자·비용 범위·model·feature 대체)을 실행 중 기록기
 * ({@link LlmUsageRecorder#sanitize})와 <b>같은 코드</b>로 적용하고, 넣다가 오류가 나는 항목(아주 긴 request_key 등)은 그 항목만
 * 세이브포인트로 되돌려 이관 전체(배포)가 멈추지 않게 하려는 것이다. 실행 중 기록과의 차이는 그 실행이 가졌던 정보만큼이다:
 * <ul>
 *   <li>model 대체: 검증은 실행의 분석기 버전(없으면 그 항목은 옮기지 않음 — 실행 중과 같음), 승인 전 분석은 generation.model,
 *       발행 뒤 분석은 결과를 저장한 시도면 generation.model, 아니면 "unknown"</li>
 *   <li>latency_ms 대체: 검증은 실행 처리 시간(duration_ms), 영향 분석은 0 (실행 중과 같음)</li>
 *   <li>시각(created_at): 그 실행이 끝난 시각 — 과거 기간 조회에 그 날짜로 잡히게</li>
 * </ul>
 * 같은 호출은 request_key 로 한 번만(ON CONFLICT DO NOTHING) — 이미 실행 중에 적힌 것과도 겹치지 않고, 다시 실행해도 결과가 같다.
 * 검증 → 승인 전 분석 → 발행 뒤 분석 순서, 각자 끝난 시각 순으로 넣으므로 같은 키가 여러 곳에 있으면 앞의 것이 남는다.
 */
public class V21__backfill_llm_usage extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V21__backfill_llm_usage.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INSERT = """
            insert into llm_usage (project_id, feature, request_key, task_id, validation_run_id, provider, model,
                                   tokens_known, input_tokens, output_tokens, cache_tokens, latency_ms,
                                   estimated_cost_amount, currency, failed, origin, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'model', ?, ?)
            on conflict (request_key) do nothing
            """;

    /** 옮길 실행 하나: 주인 정보와 usage 배열 글자 */
    private record Source(long projectId, String defaultFeature, Long taskId, Long validationRunId, String fallbackModel,
                          long fallbackLatencyMs, OffsetDateTime at, String usage, String label) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<Source> sources = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    select t.project_id, v.task_id, r.id, nullif(btrim(r.analyzer_version), '') as model,
                           coalesce(r.duration_ms, 0) as duration_ms, coalesce(r.finished_at, r.started_at, r.created_at) as at,
                           r.usage::text as usage
                      from validation_run r
                      join validation v on v.id = r.validation_id
                      join task t on t.id = v.task_id
                     where r.usage is not null
                     order by at, r.id
                    """)) {
                while (rs.next()) {
                    long run = rs.getLong("id");
                    sources.add(new Source(rs.getLong("project_id"), "TASK_VALIDATION", rs.getLong("task_id"), run,
                            rs.getString("model"), rs.getLong("duration_ms"), rs.getObject("at", OffsetDateTime.class), rs.getString("usage"),
                            "V21 validation run " + run));
                }
            }
            try (ResultSet rs = st.executeQuery("""
                    select c.id, c.project_id, nullif(btrim(c.generation ->> 'model'), '') as model,
                           coalesce(c.finished_at, c.started_at, c.requested_at) as at, c.usage::text as usage
                      from change_impact_run c
                     order by at, c.id
                    """)) {
                while (rs.next()) {
                    long run = rs.getLong("id");
                    sources.add(new Source(rs.getLong("project_id"), "CHANGE_IMPACT", null, null, unknownIfNull(rs.getString("model")),
                            0, rs.getObject("at", OffsetDateTime.class), rs.getString("usage"), "V21 impact run " + run));
                }
            }
            try (ResultSet rs = st.executeQuery("""
                    select a.intake_id, a.attempt, p.project_id, a.finished_at as at, a.usage::text as usage,
                           case when a.outcome <> 'FAILED' and a.attempt = p.attempt
                                then nullif(btrim(p.generation ->> 'model'), '') end as model
                      from published_analysis_attempt a
                      join published_change_analysis p on p.id = a.intake_id
                     order by a.finished_at, a.intake_id, a.attempt
                    """)) {
                while (rs.next()) {
                    sources.add(new Source(rs.getLong("project_id"), "CHANGE_IMPACT", null, null, unknownIfNull(rs.getString("model")),
                            0, rs.getObject("at", OffsetDateTime.class), rs.getString("usage"),
                            "V21 published analysis " + rs.getLong("intake_id") + " attempt " + rs.getInt("attempt")));
                }
            }
        }

        int inserted = 0;
        try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            for (Source source : sources) {
                if (!(parse(source) instanceof List<?> entries)) {
                    continue;
                }
                for (Object entry : entries) {
                    Row row;
                    try {
                        row = LlmUsageRecorder.sanitize(entry, source.defaultFeature(), source.fallbackModel(),
                                source.fallbackLatencyMs(), source.label());
                    } catch (RuntimeException e) {
                        // 실행 중 기록기처럼 예상 못 한 값은 그 항목만 건너뛴다 (이관 전체·배포가 멈추지 않게)
                        log.warn("{} usage entry skipped (unexpected value): {}", source.label(), e.toString());
                        continue;
                    }
                    if (row != null) {
                        inserted += insertIsolated(connection, insert, source, row);
                    }
                }
            }
        }
        log.info("V21 backfilled {} llm_usage rows from {} run records", inserted, sources.size());
    }

    private static String unknownIfNull(String model) {
        if (model == null) {
            return "unknown";
        }
        return model;
    }

    /** usage 글자를 JSON 으로. 읽지 못하면 그 실행은 건너뛴다 (DB 의 jsonb 라 실제로는 항상 읽힌다) */
    private static Object parse(Source source) {
        try {
            return JSON.readValue(source.usage(), Object.class);
        } catch (RuntimeException e) {
            log.warn("{} usage skipped (unreadable): {}", source.label(), e.toString());
            return null;
        }
    }

    /** 한 줄을 세이브포인트 안에서 넣는다. 실패하면 그 줄만 되돌리고 경고 — 실행 중 기록기와 같은 방식. 넣은 줄 수(0 또는 1) */
    private static int insertIsolated(Connection connection, PreparedStatement insert, Source source, Row row) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            insert.setLong(1, source.projectId());
            insert.setString(2, row.feature());
            insert.setString(3, row.requestKey());
            setLong(insert, 4, source.taskId());
            setLong(insert, 5, source.validationRunId());
            insert.setString(6, row.provider());
            insert.setString(7, row.model());
            insert.setBoolean(8, row.tokensKnown());
            setInt(insert, 9, row.inputTokens());
            setInt(insert, 10, row.outputTokens());
            setInt(insert, 11, row.cacheTokens());
            insert.setInt(12, row.latencyMs());
            if (row.cost() == null) {
                insert.setNull(13, Types.NUMERIC);
            } else {
                insert.setBigDecimal(13, row.cost());
            }
            insert.setString(14, row.currency());
            insert.setBoolean(15, row.failed());
            insert.setObject(16, source.at());
            insert.setObject(17, source.at());
            int count = insert.executeUpdate();
            connection.releaseSavepoint(savepoint);
            return count;
        } catch (SQLException e) {
            connection.rollback(savepoint);
            log.warn("{} usage {} not backfilled: {}", source.label(), row.requestKey(), e.getMessage());
            return 0;
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
