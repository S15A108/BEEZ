-- S15P31A108-166 컨텍스트 패킷·공유 기록(MCP-01·05~07)을 처음 쓰기 전에, 번호가 "이 프로젝트 것"인지 DB 가 지키게 한다.
-- V2 는 단일 FK(packet(id), context_record(id), agent_token(id))만 있어 다른 프로젝트 번호가 오류 없이 들어간다 (-165 리뷰와 같은 문제).
-- 두 표 모두 아직 쓰는 코드가 없어 행이 없다 — 지금 거는 게 가장 싸다.
-- 번호(V8)는 머지 직전 dev 기준으로 다시 확인한다.

-- 묶음 FK 의 대상이 되도록 (번호, 프로젝트) 유일 조건
ALTER TABLE dev_context_packet ADD CONSTRAINT uq_dev_context_packet_project UNIQUE (id, project_id);
ALTER TABLE context_record     ADD CONSTRAINT uq_context_record_project     UNIQUE (id, project_id);

-- 패킷을 만든 토큰은 같은 프로젝트 토큰
ALTER TABLE dev_context_packet
  ADD CONSTRAINT fk_dev_context_packet_token FOREIGN KEY (agent_token_id, project_id) REFERENCES agent_token(id, project_id);

-- 기록: 근거 패킷·이전 판·첫 판·작성 토큰 모두 같은 프로젝트
ALTER TABLE context_record
  ADD CONSTRAINT fk_context_record_packet   FOREIGN KEY (source_packet_id, project_id) REFERENCES dev_context_packet(id, project_id),
  ADD CONSTRAINT fk_context_record_previous FOREIGN KEY (previous_id, project_id)      REFERENCES context_record(id, project_id),
  ADD CONSTRAINT fk_context_record_root     FOREIGN KEY (root_id, project_id)          REFERENCES context_record(id, project_id),
  ADD CONSTRAINT fk_context_record_token    FOREIGN KEY (author_token_id, project_id)  REFERENCES agent_token(id, project_id);

-- 걸지 못하는 것 (코드에서 확인): task_plan_revision.context_packet_id 와 *_ref 표의 설계 항목은 표에 project_id 칸이 없다.

-- MCP 변경 도구(upsert_task_plan·report_task_state·write_context)의 같은 키 재시도 응답 (공통 5절, S15P31A108-166 리뷰 반영).
-- 키 범위는 계정·프로젝트·동작, 24시간 보존. 문서·설계 항목의 *_http_command 와 같은 방식(Q12 범위 한정 결정).
CREATE TABLE mcp_command (
  project_id  BIGINT NOT NULL REFERENCES project(id),
  account_id  BIGINT NOT NULL REFERENCES account(id),
  operation   TEXT NOT NULL,
  request_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  response    JSONB NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
  PRIMARY KEY (project_id, account_id, operation, request_key)
);

-- 계획 리비전을 저장할 때의 현재 기준 설계 (S15P31A108-166 리뷰 반영). NULL = 그때 발행된 기준이 없었음.
-- report_task_state 의 "계획 이후 변경" 기준점. 리비전 시각(앱)과 발행 시각(DB)을 비교해 추정하면 시계가 어긋날 때 변경이 빠진다.
-- task_plan_revision 은 UPDATE 금지(불변) 표라 INSERT 때만 채운다.
ALTER TABLE task_plan_revision ADD COLUMN base_baseline_id BIGINT REFERENCES baseline(id);

-- 이미 있는 리비전 채우기 (리뷰 반영). 저장 당시 기준을 따로 남기지 않았으므로 확실한 것만 채운다.
--   근거 패킷이 있으면 그 패킷의 기준 (패킷은 만들 때의 현재 기준을 저장한다. 계획 당시 기준보다 옛것일 수는 있어도 새것일 수는 없다)
--   없으면 NULL 로 둔다 = 기준을 알 수 없음 → 변경 조회는 0 부터 전부 보여 준다.
--   리비전 created_at(앱 시각)과 published_at(DB 시각)을 비교해 추정하지 않는다: 시계가 다르면 변경이 빠지고,
--   빠지는 것(누락)이 더 보여 주는 것(과다)보다 위험하다.
-- task_plan_revision 은 UPDATE 금지 트리거가 있어 이 한 번만 끄고 채운 뒤 다시 켠다 (같은 마이그레이션 트랜잭션 안).
ALTER TABLE task_plan_revision DISABLE TRIGGER trg_plan_immutable;
UPDATE task_plan_revision r
   SET base_baseline_id = k.baseline_id
  FROM dev_context_packet k
 WHERE k.id = r.context_packet_id
   AND r.base_baseline_id IS NULL;
ALTER TABLE task_plan_revision ENABLE TRIGGER trg_plan_immutable;
