package com.beez.backend.usage.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 공통 사용량 표(llm_usage)를 읽어 집계한다 (S15P31A108-169). 쓰기는 각 기능(검증 -168, 문서 분석 등)이 한다.
 * <ul>
 *   <li>같은 모델 호출은 request_key 유일 제약으로 이미 한 줄이라 그대로 센다 (실패·재시도도 실제 호출마다 한 줄)</li>
 *   <li>검증은 validation_run_id → validation_run.validation_id 로 묶는다 (llm_usage 에 검증 번호 칸이 없다)</li>
 *   <li>시각은 행을 적은 시각(created_at)이다. 기간은 [from, to)</li>
 * </ul>
 */
@Repository
public class UsageRepository {

    /** 집계 축. SQL 에 그대로 붙이므로 이 목록 밖의 값은 받지 않는다 */
    public enum Axis {
        TOTAL("'TOTAL'", null),
        FEATURE("u.feature", "u.feature"),
        // 작업·검증 번호는 숫자로 묶고 숫자 순으로 정렬한 뒤 글자로 준다 (글자 순이면 "10" 이 "9" 앞에 온다)
        TASK("cast(u.task_id as text)", "u.task_id"),
        VALIDATION("cast(r.validation_id as text)", "r.validation_id"),
        DAY("to_char((u.created_at at time zone 'Asia/Seoul')::date, 'YYYY-MM-DD')",
                "(u.created_at at time zone 'Asia/Seoul')::date");

        private final String expression;
        private final String groupKey;

        Axis(String expression, String groupKey) {
            this.expression = expression;
            this.groupKey = groupKey;
        }
    }

    /** 한 묶음의 합계. costAmount 는 기준 통화 행만 더한 값, 기준 통화 행이 없으면 null */
    public record Aggregate(String key, long inputTokens, long outputTokens, long callCount, BigDecimal costAmount,
                            long tokensUnknownCallCount, long costUnknownCallCount) {
    }

    /** 조건: 프로젝트·기간은 필수, 작업·검증·기능은 있으면 거른다 */
    public record Filter(long projectId, Instant from, Instant to, Long taskId, Long validationId, String feature) {
    }

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String FROM = """
              from llm_usage u
              left join validation_run r on r.id = u.validation_run_id
             where u.project_id = :projectId
               and u.created_at >= :from and u.created_at < :to
               and (cast(:taskId as bigint) is null or u.task_id = :taskId)
               and (cast(:validationId as bigint) is null or r.validation_id = :validationId)
               and (cast(:feature as text) is null or u.feature = :feature)
            """;

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter f) {
        return spec.param("projectId", f.projectId())
                .param("from", Timestamp.from(f.from()))
                .param("to", Timestamp.from(f.to()))
                .param("taskId", f.taskId(), java.sql.Types.BIGINT)
                .param("validationId", f.validationId(), java.sql.Types.BIGINT)
                .param("feature", f.feature(), java.sql.Types.VARCHAR);
    }

    /**
     * 비용 합계의 기준 통화: 기간·조건 안에서 추정 비용이 있는 호출이 가장 많은 통화 (같으면 글자 순). 비용 있는 호출이 없으면 빈 값.
     * 다른 통화의 비용은 환산하지 않고 "비용 모름" 으로 센다 — 서로 다른 통화를 더하지 않는다
     */
    public Optional<String> dominantCurrency(Filter f) {
        return bind(jdbc.sql("select u.currency " + FROM + """
                           and u.estimated_cost_amount is not null
                         group by u.currency
                         order by count(*) desc, u.currency
                         limit 1
                        """), f)
                .query(String.class).optional();
    }

    /**
     * axis 로 묶은 합계. TOTAL 은 언제나 한 줄. 묶음 값이 없는 행(작업·검증이 연결되지 않은 호출)은 작업·검증 축에서 빠진다 — 전체·기능·날짜 축에는 들어간다.
     * 토큰: tokens_known 이고 입력·출력이 <b>둘 다</b> 있는 호출만 두 합계에 더한다. 하나라도 모르면(검증 기록은 틀린 칸만 비우므로 한쪽만 빌 수 있다)
     * 그 호출의 토큰은 어느 합계에도 넣지 않고 tokens_unknown 으로 센다 — 같은 호출이 "모름" 과 "합계" 양쪽에 걸치지 않게
     */
    public List<Aggregate> aggregate(Filter f, Axis axis, String currency) {
        String key = axis.expression;
        String keyFilter = "";
        if (axis == Axis.TASK) {
            keyFilter = " and u.task_id is not null";
        } else if (axis == Axis.VALIDATION) {
            keyFilter = " and r.validation_id is not null";
        }
        String sql = "select " + key + " as k,"
                + """
                         coalesce(sum(u.input_tokens) filter (where u.tokens_known and u.input_tokens is not null and u.output_tokens is not null), 0) as input_tokens,
                         coalesce(sum(u.output_tokens) filter (where u.tokens_known and u.input_tokens is not null and u.output_tokens is not null), 0) as output_tokens,
                         count(*) as call_count,
                         sum(u.estimated_cost_amount) filter (where u.currency = cast(:currency as text)) as cost,
                         count(*) filter (where not (u.tokens_known and u.input_tokens is not null and u.output_tokens is not null)) as tokens_unknown,
                         count(*) filter (where u.estimated_cost_amount is null or u.currency is distinct from cast(:currency as text)) as cost_unknown
                  """
                + FROM + keyFilter;
        // 전체 합계는 묶지 않는다 (상수로 group by 하면 Postgres 가 거절하고, 묶지 않아야 호출이 없을 때도 0 한 줄이 나온다)
        if (axis.groupKey != null) {
            sql = sql + " group by " + axis.groupKey + " order by " + axis.groupKey;
        }
        List<Map<String, Object>> rows = bind(jdbc.sql(sql), f).param("currency", currency, java.sql.Types.VARCHAR)
                .query().listOfRows();
        List<Aggregate> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal cost = null;
            if (row.get("cost") != null) {
                cost = (BigDecimal) row.get("cost");
            }
            out.add(new Aggregate((String) row.get("k"), ((Number) row.get("input_tokens")).longValue(),
                    ((Number) row.get("output_tokens")).longValue(), ((Number) row.get("call_count")).longValue(), cost,
                    ((Number) row.get("tokens_unknown")).longValue(), ((Number) row.get("cost_unknown")).longValue()));
        }
        return out;
    }

    /** 작업 번호 → 표시 키(T-3). 표시 이름용 */
    public Map<Long, String> taskKeys(long projectId, List<Long> taskIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (taskIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : jdbc.sql("select id, key from task where project_id = :p and id in (:ids)")
                .param("p", projectId).param("ids", taskIds).query().listOfRows()) {
            out.put(((Number) row.get("id")).longValue(), (String) row.get("key"));
        }
        return out;
    }

    public boolean taskInProject(long projectId, long taskId) {
        return jdbc.sql("select count(*) from task where id = :t and project_id = :p")
                .param("t", taskId).param("p", projectId).query(Integer.class).single() > 0;
    }

    public boolean validationInProject(long projectId, long validationId) {
        return jdbc.sql("select count(*) from validation v join task t on t.id = v.task_id where v.id = :v and t.project_id = :p")
                .param("v", validationId).param("p", projectId).query(Integer.class).single() > 0;
    }
}
