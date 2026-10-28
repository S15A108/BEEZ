-- S15P31A108-166 컨텍스트 패킷·공유 기록(MCP-01·05~07)을 처음 쓰기 전에, 번호가 "이 프로젝트 것"인지 DB 가 지키게 한다.
-- V2 는 단일 FK(packet(id), context_record(id), agent_token(id))만 있어 다른 프로젝트 번호가 오류 없이 들어간다 (-165 리뷰와 같은 문제).
-- 두 표 모두 아직 쓰는 코드가 없어 행이 없다 — 지금 거는 게 가장 싸다.
-- 번호(V7)는 머지 직전 dev 기준으로 다시 확인한다.

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
