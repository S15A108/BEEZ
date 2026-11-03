-- S15P31A108-169 비용 조회(MON-A02)는 공통 사용량 표(llm_usage)만 읽는다. 이 표에 적지 않고 각자 실행 기록에만 남긴 과거 사용량을 옮긴다.
--   1) 검증 실행(validation_run.usage): -159 부터 남겼고 llm_usage 기록은 -168 부터라 그 사이 실행
--   2) 승인 전 변경 영향 분석(change_impact_run.usage): -169 전까지 llm_usage 에 적지 않음
--   3) 발행 뒤 변경 영향 분석 시도(published_analysis_attempt.usage): 같은 이유. 최종 결과의 사용량도 시도 행에 있다
-- 문서 분석·색인·검토는 처음부터 llm_usage 에 함께 적었다.
--
-- 런타임 기록기(LlmUsageRecorder)와 같은 규칙으로 옮긴다:
--   같은 호출은 request_key 로 한 번만 (ON CONFLICT DO NOTHING) — 이미 런타임이 적은 것과도 겹치지 않는다
--   request_key 가 없는 항목은 옮기지 않는다
--   토큰은 tokens_known=true 이고 0 이상 int 범위 정수일 때만, 비용은 통화와 함께 있고 0 이상 NUMERIC(12,6) 범위일 때만 (아니면 그 칸만 비움)
--   model: 항목 → 실행의 model (검증은 분석기 버전, 영향 분석은 generation.model) → 검증은 없으면 옮기지 않고, 영향 분석은 'unknown'
--   feature: 항목 값이 허용 목록 안이면 그 값, 아니면 그 실행의 기능
--   시각(created_at): 그 실행이 끝난 시각 — 과거 기간 조회에 그 날짜로 잡히게
-- 형식이 틀린 값 때문에 이관 전체가 실패하지 않도록 변환 전에 글자 모양을 먼저 본다 (CASE 를 겹쳐 써서 확인 뒤에만 변환된다).

CREATE OR REPLACE FUNCTION pg_temp.usage_int(v jsonb) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN jsonb_typeof(v) IN ('number','string') AND (v #>> '{}') ~ '^[0-9]{1,10}$'
              THEN CASE WHEN (v #>> '{}')::bigint <= 2147483647 THEN (v #>> '{}')::int END END
$$;

CREATE OR REPLACE FUNCTION pg_temp.usage_cost(v jsonb) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN jsonb_typeof(v) IN ('number','string') AND (v #>> '{}') ~ '^[0-9]{1,6}(\.[0-9]+)?$'
              THEN CASE WHEN round((v #>> '{}')::numeric, 6) <= 999999.999999 THEN round((v #>> '{}')::numeric, 6) END END
$$;

WITH usage_backfill (project_id, feature, entry, task_id, validation_run_id, model, at) AS (
  -- 1) 검증 실행
  SELECT t.project_id, 'TASK_VALIDATION', e, v.task_id, r.id,
         coalesce(nullif(e ->> 'model', ''), nullif(r.analyzer_version, '')),
         coalesce(r.finished_at, r.started_at, r.created_at)
    FROM validation_run r
    JOIN validation v ON v.id = r.validation_id
    JOIN task t ON t.id = v.task_id
    CROSS JOIN LATERAL jsonb_array_elements(r.usage) e
   WHERE r.usage IS NOT NULL AND jsonb_typeof(r.usage) = 'array'
  UNION ALL
  -- 2) 승인 전 변경 영향 분석
  SELECT c.project_id, 'CHANGE_IMPACT', e, NULL::bigint, NULL::bigint,
         coalesce(nullif(e ->> 'model', ''), nullif(c.generation ->> 'model', ''), 'unknown'),
         coalesce(c.finished_at, c.started_at, c.requested_at)
    FROM change_impact_run c
    CROSS JOIN LATERAL jsonb_array_elements(c.usage) e
   WHERE jsonb_typeof(c.usage) = 'array'
  UNION ALL
  -- 3) 발행 뒤 변경 영향 분석 시도
  SELECT p.project_id, 'CHANGE_IMPACT', e, NULL::bigint, NULL::bigint,
         coalesce(nullif(e ->> 'model', ''), 'unknown'),
         a.finished_at
    FROM published_analysis_attempt a
    JOIN published_change_analysis p ON p.id = a.intake_id
    CROSS JOIN LATERAL jsonb_array_elements(a.usage) e
   WHERE jsonb_typeof(a.usage) = 'array'
)
INSERT INTO llm_usage (project_id, feature, request_key, task_id, validation_run_id, provider, model, tokens_known,
                       input_tokens, output_tokens, cache_tokens, latency_ms, estimated_cost_amount, currency, failed, origin,
                       created_at, updated_at)
SELECT project_id, feature, request_key, task_id, validation_run_id, provider, model, known,
       CASE WHEN known THEN pg_temp.usage_int(entry -> 'input_tokens') END,
       CASE WHEN known THEN pg_temp.usage_int(entry -> 'output_tokens') END,
       pg_temp.usage_int(entry -> 'cache_tokens'),
       coalesce(pg_temp.usage_int(entry -> 'latency_ms'), 0),
       CASE WHEN currency IS NOT NULL THEN pg_temp.usage_cost(entry -> 'estimated_cost_amount') END,
       CASE WHEN currency IS NOT NULL AND pg_temp.usage_cost(entry -> 'estimated_cost_amount') IS NOT NULL THEN currency END,
       failed, 'model', at, at
  FROM (SELECT b.project_id,
               CASE WHEN b.entry ->> 'feature' IN ('DOCUMENT_PARSE','TASK_VALIDATION','CHANGE_IMPACT','EMBEDDING','BEE_CHAT',
                                                   'BEE_DRAFT','TASK_ASSIGNMENT','MEETING_ANALYSIS')
                    THEN b.entry ->> 'feature' ELSE b.feature END AS feature,
               b.entry ->> 'request_key' AS request_key,
               b.task_id, b.validation_run_id,
               coalesce(nullif(b.entry ->> 'provider', ''), 'unspecified') AS provider,
               b.model,
               coalesce((b.entry -> 'tokens_known') = 'true'::jsonb, false) AS known,
               coalesce((b.entry -> 'failed') = 'true'::jsonb, false) AS failed,
               CASE WHEN jsonb_typeof(b.entry -> 'currency') = 'string' AND b.entry ->> 'currency' <> ''
                    THEN b.entry ->> 'currency' END AS currency,
               b.entry, b.at
          FROM usage_backfill b
         WHERE jsonb_typeof(b.entry) = 'object'
           AND jsonb_typeof(b.entry -> 'request_key') = 'string'
           AND b.entry ->> 'request_key' <> ''
           AND b.model IS NOT NULL
         ORDER BY b.at, b.validation_run_id) s
ON CONFLICT (request_key) DO NOTHING;
