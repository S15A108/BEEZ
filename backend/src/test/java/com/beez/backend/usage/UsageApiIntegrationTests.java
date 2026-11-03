package com.beez.backend.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 프로젝트 AI 사용량·추정 비용 조회 (S15P31A108-169, MON-A02). 사용량 행은 각 기능이 적는 모양 그대로 SQL 로 넣는다 */
class UsageApiIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** 한국 시간 11/1 00:00 ~ 11/3 00:00 */
    static final String FROM = "2026-10-31T15:00:00Z";
    static final String TO = "2026-11-02T15:00:00Z";

    @Autowired
    DataSource dataSource;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    Long outsider;
    int keySeq;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
    }

    // ---- 도우미 ----

    private long createTask(String title) throws Exception {
        HttpResponse<String> res = api.post("/api/projects/" + projectId + "/tasks", api.planJson(title, api.readsRef("API-01"), "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return TaskApiSupport.longField(res.body(), "id");
    }

    /** 작업의 현재 계획에 검증 한 건과 실행(시도 1) 한 건 → {validationId, runId} */
    private long[] validationRun(long taskId) {
        Long revision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId).query(Long.class).single();
        Long baseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();
        long v = api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'PASS', 'AGENT', 'h', 'r1') returning id
                        """).param("t", taskId).param("r", revision).param("b", baseline).query(Long.class).single();
        long run = api.jdbc.sql("""
                        insert into validation_run (validation_id, attempt, kind, status) values (:v, 1, 'MODEL', 'OK') returning id
                        """).param("v", v).query(Long.class).single();
        return new long[] {v, run};
    }

    /** llm_usage 한 줄. tokens 가 null 이면 tokens_known=false, cost 가 null 이면 비용·통화 없음 */
    private void usage(String feature, Long taskId, Long runId, Integer input, Integer output, String cost, String currency,
                       boolean failed, String createdAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("p", projectId);
        p.put("f", feature);
        p.put("k", "test-call-" + (++keySeq));
        p.put("t", taskId);
        p.put("r", runId);
        p.put("known", input != null && output != null);
        p.put("in", input);
        p.put("out", output);
        p.put("cost", cost);
        p.put("cur", currency);
        p.put("failed", failed);
        p.put("at", createdAt);
        api.jdbc.sql("""
                        insert into llm_usage (project_id, feature, request_key, task_id, validation_run_id, provider, model, tokens_known,
                                               input_tokens, output_tokens, latency_ms, estimated_cost_amount, currency, failed, origin, created_at)
                        values (:p, :f, :k, cast(:t as bigint), cast(:r as bigint), 'test', 'm', :known, cast(:in as int), cast(:out as int), 10,
                                cast(:cost as numeric), cast(:cur as text), :failed, 'model', cast(:at as timestamptz))
                        """).params(p).update();
    }

    private JsonNode get(String query, Long account, int status) throws Exception {
        HttpResponse<String> res = api.get("/api/projects/" + projectId + "/usage" + query, account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return JSON.readTree(res.body());
    }

    private static String q(String from, String to) {
        return "?from=" + URLEncoder.encode(from, StandardCharsets.UTF_8) + "&to=" + URLEncoder.encode(to, StandardCharsets.UTF_8);
    }

    private static Map<String, JsonNode> byKey(JsonNode list) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode n : list) {
            out.put(n.get("key").asText(), n);
        }
        return out;
    }

    private static List<String> keys(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) {
            out.add(n.get("key").asText());
        }
        return out;
    }

    /** 묶음들의 합이 전체와 같은가 (호출 수·토큰·모르는 호출 수·비용) */
    private static void assertSumsMatch(JsonNode total, JsonNode buckets) {
        long calls = 0;
        long input = 0;
        long output = 0;
        long tokensUnknown = 0;
        long costUnknown = 0;
        java.math.BigDecimal cost = java.math.BigDecimal.ZERO;
        for (JsonNode b : buckets) {
            calls += b.get("call_count").asLong();
            input += b.get("input_tokens").asLong();
            output += b.get("output_tokens").asLong();
            tokensUnknown += b.get("tokens_unknown_call_count").asLong();
            costUnknown += b.get("cost_unknown_call_count").asLong();
            if (b.has("estimated_cost_amount")) {
                cost = cost.add(new java.math.BigDecimal(b.get("estimated_cost_amount").asText()));
            }
        }
        assertThat(calls).isEqualTo(total.get("call_count").asLong());
        assertThat(input).isEqualTo(total.get("input_tokens").asLong());
        assertThat(output).isEqualTo(total.get("output_tokens").asLong());
        assertThat(tokensUnknown).isEqualTo(total.get("tokens_unknown_call_count").asLong());
        assertThat(costUnknown).isEqualTo(total.get("cost_unknown_call_count").asLong());
        if (total.has("estimated_cost_amount")) {
            assertThat(cost).isEqualByComparingTo(total.get("estimated_cost_amount").asText());
        }
    }

    // ---- 시험 ----

    @Test
    void summary_includesUnlinkedCalls_keepsUnknownSeparate_andBucketsAddUpToTotal() throws Exception {
        long t1 = createTask("검증 1");
        long t2 = createTask("검증 2");
        long[] v1 = validationRun(t1);
        long[] v2 = validationRun(t2);
        usage("TASK_VALIDATION", t1, v1[1], 1000, 200, "0.010000", "USD", false, "2026-11-01T01:00:00+09:00");
        usage("TASK_VALIDATION", t1, v1[1], 500, 100, "0.005", "USD", true, "2026-11-01T23:59:00+09:00");   // 실패 호출도 센다
        usage("DOCUMENT_PARSE", null, null, 300, 50, null, null, false, "2026-11-02T00:00:00+09:00");        // 작업 없음·단가 없음
        usage("BEE_CHAT", null, null, null, null, "0.002", "USD", false, "2026-11-02T10:00:00+09:00");      // 토큰 모름
        usage("TASK_VALIDATION", t2, v2[1], 100, 10, "7", "KRW", false, "2026-11-02T12:00:00+09:00");        // 다른 통화
        usage("TASK_VALIDATION", t1, v1[1], 9999, 9999, "9", "USD", false, "2026-10-31T23:59:59+09:00");     // 기간 앞 — 빠진다
        usage("TASK_VALIDATION", t1, v1[1], 9999, 9999, "9", "USD", false, "2026-11-03T00:00:00+09:00");     // 끝은 미포함 — 빠진다

        JsonNode body = get(q(FROM, TO), member, 200);
        assertThat(body.get("call_count").asLong()).isEqualTo(5);
        assertThat(body.get("input_tokens").asLong()).isEqualTo(1900);
        assertThat(body.get("output_tokens").asLong()).isEqualTo(360);
        assertThat(body.get("tokens_unknown_call_count").asLong()).isEqualTo(1);
        assertThat(body.get("currency").asText()).isEqualTo("USD");                    // 비용 있는 호출이 가장 많은 통화
        assertThat(body.get("estimated_cost_amount").asText()).isEqualTo("0.017");     // KRW 는 더하지 않는다
        assertThat(body.get("cost_unknown_call_count").asLong()).isEqualTo(2);         // 단가 없음 + 다른 통화

        // 기능별: 8개 기능이 정해진 순서로, 호출 없는 기능은 0
        assertThat(keys(body.get("by_feature"))).containsExactly("DOCUMENT_PARSE", "TASK_VALIDATION", "CHANGE_IMPACT", "EMBEDDING",
                "BEE_CHAT", "BEE_DRAFT", "TASK_ASSIGNMENT", "MEETING_ANALYSIS");
        Map<String, JsonNode> feature = byKey(body.get("by_feature"));
        assertThat(feature.get("TASK_VALIDATION").get("call_count").asLong()).isEqualTo(3);
        assertThat(feature.get("TASK_VALIDATION").get("estimated_cost_amount").asText()).isEqualTo("0.015");
        assertThat(feature.get("DOCUMENT_PARSE").get("estimated_cost_amount").asText()).isEqualTo("0");
        assertThat(feature.get("DOCUMENT_PARSE").get("cost_unknown_call_count").asLong()).isEqualTo(1);
        assertThat(feature.get("EMBEDDING").get("call_count").asLong()).isZero();
        assertSumsMatch(body, body.get("by_feature"));

        // 날짜별: 한국 날짜, 기간 안의 날을 모두
        assertThat(keys(body.get("by_day"))).containsExactly("2026-11-01", "2026-11-02");
        Map<String, JsonNode> day = byKey(body.get("by_day"));
        assertThat(day.get("2026-11-01").get("call_count").asLong()).isEqualTo(2);
        assertThat(day.get("2026-11-02").get("call_count").asLong()).isEqualTo(3);
        assertSumsMatch(body, body.get("by_day"));

        // 작업별·검증별: 연결된 호출만, 작업은 표시 키를 label 로
        Map<String, JsonNode> task = byKey(body.get("by_task"));
        assertThat(task.keySet()).containsExactly(String.valueOf(t1), String.valueOf(t2));
        assertThat(task.get(String.valueOf(t1)).get("call_count").asLong()).isEqualTo(2);
        assertThat(task.get(String.valueOf(t1)).get("label").asText()).startsWith("T-");
        assertThat(task.get(String.valueOf(t2)).get("estimated_cost_amount").asText()).isEqualTo("0");
        assertThat(task.get(String.valueOf(t2)).get("cost_unknown_call_count").asLong()).isEqualTo(1);
        Map<String, JsonNode> validation = byKey(body.get("by_validation"));
        assertThat(validation.keySet()).containsExactly(String.valueOf(v1[0]), String.valueOf(v2[0]));
        assertThat(validation.get(String.valueOf(v1[0])).get("input_tokens").asLong()).isEqualTo(1500);

        // 에이전트별은 호출자 기록이 없어 비어 있다
        assertThat(body.get("by_agent")).isEmpty();
    }

    @Test
    void filters_byTaskValidationFeature_andCostOmittedWhenNoPrice() throws Exception {
        long t1 = createTask("검증 1");
        long t2 = createTask("검증 2");
        long[] v1 = validationRun(t1);
        long[] v2 = validationRun(t2);
        usage("TASK_VALIDATION", t1, v1[1], 1000, 200, "0.01", "USD", false, "2026-11-01T01:00:00+09:00");
        usage("TASK_VALIDATION", t2, v2[1], 100, 10, "0.002", "USD", false, "2026-11-01T02:00:00+09:00");
        usage("DOCUMENT_PARSE", null, null, 300, 50, null, null, false, "2026-11-01T03:00:00+09:00");

        JsonNode byTask = get(q(FROM, TO) + "&task_id=" + t1, leader, 200);
        assertThat(byTask.get("call_count").asLong()).isEqualTo(1);
        assertThat(byTask.get("estimated_cost_amount").asText()).isEqualTo("0.01");
        assertThat(keys(byTask.get("by_task"))).containsExactly(String.valueOf(t1));
        assertSumsMatch(byTask, byTask.get("by_feature"));

        JsonNode byValidation = get(q(FROM, TO) + "&validation_id=" + v2[0], member, 200);
        assertThat(byValidation.get("call_count").asLong()).isEqualTo(1);
        assertThat(keys(byValidation.get("by_validation"))).containsExactly(String.valueOf(v2[0]));

        // 단가 있는 호출이 하나도 없으면 비용·통화를 생략한다 (0원으로 보이지 않게)
        JsonNode parse = get(q(FROM, TO) + "&feature=DOCUMENT_PARSE", member, 200);
        assertThat(parse.get("call_count").asLong()).isEqualTo(1);
        assertThat(parse.has("estimated_cost_amount")).isFalse();
        assertThat(parse.has("currency")).isFalse();
        assertThat(parse.get("cost_unknown_call_count").asLong()).isEqualTo(1);
        assertThat(byKey(parse.get("by_feature")).get("DOCUMENT_PARSE").has("estimated_cost_amount")).isFalse();

        // 두 조건을 함께 주면 둘 다 맞는 것만
        JsonNode none = get(q(FROM, TO) + "&task_id=" + t1 + "&validation_id=" + v2[0], member, 200);
        assertThat(none.get("call_count").asLong()).isZero();
    }

    @Test
    void emptyPeriod_givesZeros_andDayRowsInKoreanDates() throws Exception {
        JsonNode body = get(q("2026-11-01T00:00:00+09:00", "2026-11-01T00:00:01+09:00"), member, 200);
        assertThat(body.get("call_count").asLong()).isZero();
        assertThat(body.get("input_tokens").asLong()).isZero();
        assertThat(body.has("estimated_cost_amount")).isFalse();
        assertThat(keys(body.get("by_day"))).containsExactly("2026-11-01");
        assertThat(body.get("by_feature")).hasSize(8);
        assertThat(body.get("by_task")).isEmpty();
        assertThat(body.get("by_validation")).isEmpty();
    }

    @Test
    void errors_badPeriodFeatureFilterAndAccess() throws Exception {
        get(q(TO, FROM), member, 400);                                                  // from 이 to 보다 뒤
        get(q(FROM, FROM), member, 400);                                                // 같으면 빈 기간
        get("?from=2026-11-01&to=" + URLEncoder.encode(TO, StandardCharsets.UTF_8), member, 400);   // 오프셋 없는 시각
        get("?to=" + URLEncoder.encode(TO, StandardCharsets.UTF_8), member, 400);       // from 없음
        get(q("2025-01-01T00:00:00Z", "2026-02-01T00:00:00Z"), member, 400);            // 366일 넘음
        get(q(FROM, TO) + "&feature=UNKNOWN", member, 400);
        get(q(FROM, TO) + "&task_id=abc", member, 400);
        get(q(FROM, TO) + "&task_id=999999", member, 404);                              // 이 프로젝트 작업이 아님
        get(q(FROM, TO) + "&validation_id=999999", member, 404);
        get(q(FROM, TO), outsider, 404);
        get(q(FROM, TO), null, 401);
    }
}
