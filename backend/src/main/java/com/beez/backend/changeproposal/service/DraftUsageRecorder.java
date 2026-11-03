package com.beez.backend.changeproposal.service;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 문서 수정 초안(CHG-A07)의 모델 사용량을 공통 사용량 표({@code llm_usage}, feature BEE_DRAFT)에 남긴다.
 * 호출 한 번마다 {@code request_key} 가 새로 만들어지므로 같은 요청을 다시 보내도 실제로 일어난 호출은 모두 센다.
 * 기록이 실패해도 응답을 막지 않는다(경고 로그). 트랜잭션 밖에서 호출한다 — 한 줄 실패가 다른 줄이나 응답을 되돌리지 않게 한다.
 */
@Component
public class DraftUsageRecorder {
    private static final Logger log = LoggerFactory.getLogger(DraftUsageRecorder.class);
    static final String FEATURE = "BEE_DRAFT";
    private static final BigDecimal COST_MAX = new BigDecimal("999999.999999");

    private final JdbcClient jdbc;
    public DraftUsageRecorder(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** AI 응답의 usage 배열을 한 줄씩 기록한다. 기록을 시도한 줄 수를 돌려준다. */
    public int record(long project, Long task, String requestKey, Map<String, Object> response, long observedMillis) {
        if (!(response.get("usage") instanceof List<?> list)) return 0;
        return recordLines(project, task, requestKey, list, "TEMPLATE".equals(response.get("origin")), response.get("model"), observedMillis);
    }

    /**
     * usage 줄 목록을 한 줄씩 기록한다. 성공 응답의 usage 와 오류 본문의 usage(모델 호출 뒤 실패, 실제 토큰·비용) 모두 이 경로다.
     * 기록을 시도한 줄 수를 돌려준다 — 0 이면 호출한 쪽이 "미확인" 한 줄을 따로 남긴다.
     */
    public int recordLines(long project, Long task, String requestKey, List<?> list, boolean template, Object responseModel,
                           long observedMillis) {
        int tried = 0;
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof Map<?, ?> raw)) continue;
            tried++;
            @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) raw;
            insertQuietly(project, task, entry, requestKey + ":" + index, responseModel, template, observedMillis);
        }
        return tried;
    }

    /** 호출이 실패했거나 쓸 수 없는 응답이 왔을 때. 토큰 수를 모르므로 0 으로 채우지 않는다. */
    public void recordFailure(long project, Long task, String requestKey, long observedMillis) {
        insertQuietly(project, task, Map.of("failed", true, "tokens_known", false), requestKey + ":failed", null, false, observedMillis);
    }

    private void insertQuietly(long project, Long task, Map<String, Object> entry, String fallbackKey, Object responseModel,
                               boolean template, long observedMillis) {
        try {
            boolean known = Boolean.TRUE.equals(entry.get("tokens_known"));
            String key = entry.get("request_key") instanceof String k && !k.isBlank() ? k : fallbackKey;
            String model = entry.get("model") instanceof String m && !m.isBlank() ? m
                    : responseModel instanceof String rm && !rm.isBlank() ? rm : "unknown";
            String provider = entry.get("provider") instanceof String p && !p.isBlank() ? p : "unspecified";
            BigDecimal cost = null;
            String currency = null;
            if (entry.get("estimated_cost_amount") != null && entry.get("currency") instanceof String c && !c.isBlank()) {
                cost = cost(entry.get("estimated_cost_amount"));
                if (cost != null) currency = c;
            }
            Integer latency = nonNegativeInt(entry.get("latency_ms"));
            if (latency == null) latency = (int) Math.min(Integer.MAX_VALUE, Math.max(0, observedMillis));
            jdbc.sql("""
                    INSERT INTO llm_usage(project_id,feature,request_key,task_id,provider,model,tokens_known,input_tokens,output_tokens,
                                          cache_tokens,latency_ms,estimated_cost_amount,currency,failed,origin)
                    VALUES(:project,:feature,:key,:task,:provider,:model,:known,:input,:output,:cache,:latency,:cost,:currency,:failed,:origin)
                    ON CONFLICT(request_key) DO NOTHING
                    """)
                    .param("project", project).param("feature", FEATURE).param("key", key).param("task", task, Types.BIGINT)
                    .param("provider", provider).param("model", model).param("known", known)
                    .param("input", known ? nonNegativeInt(entry.get("input_tokens")) : null, Types.INTEGER)
                    .param("output", known ? nonNegativeInt(entry.get("output_tokens")) : null, Types.INTEGER)
                    .param("cache", nonNegativeInt(entry.get("cache_tokens")), Types.INTEGER).param("latency", latency)
                    .param("cost", cost, Types.NUMERIC).param("currency", currency, Types.VARCHAR)
                    .param("failed", Boolean.TRUE.equals(entry.get("failed"))).param("origin", template ? "template" : "model")
                    .update();
        } catch (RuntimeException e) {
            log.warn("BEE_DRAFT usage not recorded (project {}): {}", project, e.getMessage());
        }
    }

    /** 0 이상의 int 로 읽을 수 있으면 그 값, 아니면 null. */
    static Integer nonNegativeInt(Object value) {
        if (value == null) return null;
        try {
            BigDecimal d = new BigDecimal(String.valueOf(value).trim());
            return d.signum() < 0 ? null : d.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** llm_usage NUMERIC(12,6) 에 넣을 수 있는 비용. 숫자가 아니거나 음수·범위 밖이면 null. */
    static BigDecimal cost(Object value) {
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value).trim());
            if (amount.signum() < 0 || amount.compareTo(COST_MAX) > 0) return null;
            return amount.setScale(6, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
