package com.beez.backend.usage.repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * AI 응답의 사용량 배열(명세 Usage[])을 공통 사용량 표(llm_usage)에 한 줄씩 넣는다. 비용 조회(-169)는 이 표만 읽으므로 모델을 부르는
 * 기능은 모두 여기로 기록한다 — 검증(-168, ValidationRepository)과 변경 영향 분석(승인 전 분석·발행 뒤 분석, -169 연결).
 * 문서 분석·검토는 각자 같은 표에 직접 넣는다.
 * <ul>
 *   <li>같은 호출은 한 번만: request_key 가 유일 제약이라 겹치면 건너뛴다 (늦은 응답·재전송·재시도가 같은 키로 와도 두 번 합산되지 않음)</li>
 *   <li>tokens_known=false 면 토큰 칸을 비운다 (모르는 값을 0 으로 만들지 않음). 추정 비용은 통화와 함께 있을 때만</li>
 *   <li>request_key 가 없는 항목은 적지 않고 경고만 남긴다. model 이 없으면 호출한 쪽이 준 대체 값을 쓰고, 그것도 없으면 적지 않는다</li>
 *   <li>값이 틀려도 호출한 쪽의 결과 저장을 막지 않는다 — 넣기 전에 모양·범위를 확인해 틀린 칸은 비우고(추정 비용이 숫자가 아니거나
 *       NUMERIC(12,6) 범위 밖, 토큰·지연이 정수 범위 밖·음수 등), 그래도 DB 오류가 나면 그 항목만 세이브포인트로 되돌리고 경고를 남긴다
 *       (Postgres 는 오류 난 트랜잭션을 그대로 못 쓰므로 잡기만 해서는 안 되고 되돌려야 한다)</li>
 * </ul>
 * 호출한 쪽의 트랜잭션 안에서 부른다.
 */
@Repository
public class LlmUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);

    /** llm_usage.feature CHECK 와 같은 값. 항목의 feature 가 이 밖이면 호출한 쪽의 기본 기능으로 적는다 (제약 위반으로 저장 전체가 실패하지 않게) */
    public static final Set<String> FEATURES = Set.of("DOCUMENT_PARSE", "TASK_VALIDATION", "CHANGE_IMPACT", "EMBEDDING", "BEE_CHAT",
            "BEE_DRAFT", "TASK_ASSIGNMENT", "MEETING_ANALYSIS");

    /**
     * 사용량의 주인.
     *
     * @param defaultFeature    항목에 feature 가 없거나 모르는 값일 때 쓸 기능
     * @param taskId            작업에 묶인 호출이면 그 작업 (없으면 null)
     * @param validationRunId   검증 실행이면 그 실행 (없으면 null)
     * @param fallbackModel     항목에 model 이 없을 때 쓸 값 (응답 최상위 model 등). null 이면 model 없는 항목은 적지 않는다
     * @param label             경고 로그에 붙일 이름 (예: "validation run 12")
     */
    public record Owner(long projectId, String defaultFeature, Long taskId, Long validationRunId, String fallbackModel, String label) {
    }

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public LlmUsageRecorder(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * @param usage             AI 응답의 usage 배열. 배열이 아니거나 비었으면 아무것도 하지 않는다
     * @param fallbackLatencyMs 항목에 latency_ms 가 없을 때 쓸 값 (호출 전체 시간)
     */
    public void record(Owner owner, Object usage, long fallbackLatencyMs) {
        if (!(usage instanceof List<?> entries) || entries.isEmpty()) {
            return;
        }
        for (Object value : entries) {
            try {
                recordEntry(owner, value, fallbackLatencyMs);
            } catch (RuntimeException e) {
                // 값 거르기에서 미처 못 잡은 예외(수치 변환 등)도 저장을 막지 않는다. DB 오류는 insertIsolated 가 따로 되돌린다
                log.warn("{} usage entry skipped (unexpected value): {} — {}", owner.label(), value, e.toString());
            }
        }
    }

    /**
     * 표에 넣을 한 줄. {@link #sanitize} 가 만든다 — 실행 중 기록과 과거 기록 이관(V21)이 같은 거르기 규칙을 쓰도록 한 곳에 둔다.
     * input·output·cache·cost·currency 는 비어 있을 수 있다
     */
    public record Row(String requestKey, String feature, String provider, String model, boolean tokensKnown, Integer inputTokens,
                      Integer outputTokens, Integer cacheTokens, int latencyMs, BigDecimal cost, String currency, boolean failed) {
    }

    /**
     * 사용량 항목 하나를 표에 넣을 값으로 거른다. 못 쓰는 항목(객체가 아님, request_key 없음, model 을 알 수 없음)이면 null.
     * 틀린 칸은 비운다: 토큰은 tokens_known=true 이고 0 이상 int 일 때만, 추정 비용은 통화와 함께 있고 NUMERIC(12,6) 에 들어갈 때만.
     *
     * @param defaultFeature    항목의 feature 가 없거나 모르는 값일 때
     * @param fallbackModel     항목에 model 이 없을 때. null 이면 그런 항목은 버린다
     * @param fallbackLatencyMs 항목에 latency_ms 가 없을 때
     * @param label             경고 로그에 붙일 이름
     */
    public static Row sanitize(Object value, String defaultFeature, String fallbackModel, long fallbackLatencyMs, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            entry.put(String.valueOf(e.getKey()), e.getValue());
        }
        Object key = entry.get("request_key");
        if (!(key instanceof String k) || k.isBlank()) {
            log.warn("{} usage entry skipped (request_key missing): {}", label, entry.keySet());
            return null;
        }
        String model = fallbackModel;
        if (entry.get("model") instanceof String m && !m.isBlank()) {
            model = m;
        }
        if (model == null || model.isBlank()) {
            log.warn("{} usage {} skipped (model missing)", label, k);
            return null;
        }
        boolean known = Boolean.TRUE.equals(entry.get("tokens_known"));
        Integer input = null;
        Integer output = null;
        if (known) {
            input = nonNegativeInt(entry.get("input_tokens"));
            output = nonNegativeInt(entry.get("output_tokens"));
        }
        Integer latency = nonNegativeInt(entry.get("latency_ms"));
        if (latency == null) {
            latency = (int) Math.min(Integer.MAX_VALUE, Math.max(0, fallbackLatencyMs));
        }
        String feature = defaultFeature;
        if (entry.get("feature") instanceof String f && FEATURES.contains(f)) {
            feature = f;
        }
        String provider = "unspecified";
        if (entry.get("provider") instanceof String pv && !pv.isBlank()) {
            provider = pv;
        }
        BigDecimal cost = null;
        String currency = null;
        if (entry.get("estimated_cost_amount") != null && entry.get("currency") instanceof String cur && !cur.isBlank()) {
            cost = costAmount(entry.get("estimated_cost_amount"));
            if (cost == null) {
                log.warn("{} usage {}: estimated_cost_amount not a valid amount, stored without cost", label, k);
            } else {
                currency = cur;
            }
        }
        return new Row(k, feature, provider, model, known, input, output, nonNegativeInt(entry.get("cache_tokens")), latency, cost,
                currency, Boolean.TRUE.equals(entry.get("failed")));
    }

    private void recordEntry(Owner owner, Object value, long fallbackLatencyMs) {
        Row row = sanitize(value, owner.defaultFeature(), owner.fallbackModel(), fallbackLatencyMs, owner.label());
        if (row == null) {
            return;
        }
        JdbcClient.StatementSpec insert = jdbc.sql(INSERT_SQL)
                .param("project", owner.projectId()).param("feature", row.feature()).param("key", row.requestKey())
                .param("task", owner.taskId(), Types.BIGINT).param("run", owner.validationRunId(), Types.BIGINT)
                .param("provider", row.provider()).param("model", row.model())
                .param("known", row.tokensKnown()).param("input", row.inputTokens(), Types.INTEGER)
                .param("output", row.outputTokens(), Types.INTEGER).param("cache", row.cacheTokens(), Types.INTEGER)
                .param("latency", row.latencyMs()).param("cost", row.cost(), Types.NUMERIC)
                .param("currency", row.currency(), Types.VARCHAR).param("failed", row.failed());
        insertIsolated(owner.label(), row.requestKey(), insert);
    }

    private static final String INSERT_SQL = """
            insert into llm_usage (project_id, feature, request_key, task_id, validation_run_id, provider, model,
                                   tokens_known, input_tokens, output_tokens, cache_tokens, latency_ms,
                                   estimated_cost_amount, currency, failed, origin)
            values (:project, :feature, :key, :task, :run, :provider, :model,
                    :known, :input, :output, :cache, :latency, :cost, :currency, :failed, 'model')
            on conflict (request_key) do nothing
            """;

    /**
     * 사용량 한 줄을 세이브포인트 안에서 넣는다. 실패하면 그 줄만 되돌리고 경고 — 호출한 쪽의 저장은 그대로 커밋된다.
     * (DataSourceUtils.getConnection: 지금 트랜잭션이 쓰는 그 연결을 받는다. Savepoint: 트랜잭션 안의 되돌릴 지점)
     */
    private void insertIsolated(String label, String requestKey, JdbcClient.StatementSpec insert) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint savepoint = connection.setSavepoint();
            try {
                insert.update();
                connection.releaseSavepoint(savepoint);
            } catch (DataAccessException e) {
                connection.rollback(savepoint);
                log.warn("{} usage {} not recorded in llm_usage: {}", label, requestKey, e.getMessage());
            }
        } catch (SQLException e) {
            log.warn("{} usage {} not recorded in llm_usage (savepoint failed): {}", label, requestKey, e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal COST_MAX = new BigDecimal("999999.999999");
    private static final BigDecimal COST_HALF_UNIT = new BigDecimal("0.0000005");

    /**
     * 0 이상의 int 로 읽을 수 있으면 그 값, 아니면 null (문자열 숫자도 받는다. 소수·음수·int 범위 밖·글자는 null).
     * 자르지 않고 비교한다 — 아주 큰 정수(JSON 에서 BigInteger 로 옴)를 long 으로 바꾸면 윗자리가 잘려 범위 검사를 통과할 수 있다
     */
    public static Integer nonNegativeInt(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal d;
        try {
            d = new BigDecimal(String.valueOf(value).trim());      // Number 든 문자열이든 10진 표기를 그대로 읽는다 (잘림 없음)
        } catch (NumberFormatException e) {
            return null;
        }
        if (d.signum() < 0 || d.compareTo(INT_MAX) > 0) {
            return null;
        }
        try {
            return d.intValueExact();                                // 소수 부분이 있으면 ArithmeticException → null
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * 명세 Usage.estimated_cost_amount(문자열)를 llm_usage NUMERIC(12,6) 에 넣을 수 있는 값으로. 숫자가 아니거나 음수·범위 밖이면 null.
     * 자릿수 맞추기(setScale) 전에 범위를 본다 — 지수가 큰 값(1e2147483647)은 자릿수를 맞추려는 순간 예외가 난다
     */
    public static BigDecimal costAmount(Object value) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount.signum() < 0 || amount.compareTo(COST_MAX) > 0) {
            return null;
        }
        if (amount.compareTo(COST_HALF_UNIT) < 0) {
            return BigDecimal.ZERO.setScale(6);        // 반올림하면 0 인 값. 지수가 아주 작은 값(1e-2147483647)을 나누기 전에 거른다
        }
        try {
            BigDecimal scaled = amount.setScale(6, java.math.RoundingMode.HALF_UP);   // 소수 6자리로 (반올림)
            if (scaled.compareTo(COST_MAX) > 0) {
                return null;                                                           // 반올림으로 상한을 넘은 경우
            }
            return scaled;
        } catch (ArithmeticException e) {
            return null;                                                               // 매우 작은 지수(1e-2147483647) 등
        }
    }
}
