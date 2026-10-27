-- S15P31A108-165 컨텍스트 조회 이력: 사람 호출자(세션)를 적을 계정 칸이 없어 추가한다.
-- 에이전트 호출이면 토큰 소유 계정, 사람 세션이면 그 계정. 항상 채운다 (기존 행이 없어 NOT NULL 로 둔다).
-- 번호(V5)는 머지 직전 dev 기준으로 다시 확인한다 (V3 -192, V4 -174 가 먼저 잡았다).
ALTER TABLE context_access_log
  ADD COLUMN account_id BIGINT NOT NULL REFERENCES account(id);

-- 이력은 고치지 않는다 (append-only). 다른 불변 테이블과 같은 트리거.
CREATE TRIGGER trg_context_access_log_immutable BEFORE UPDATE ON context_access_log
  FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- 프로젝트 이력 목록(최신순)과 작업별 조회
CREATE INDEX ix_context_access_log_project ON context_access_log(project_id, id DESC);
CREATE INDEX ix_context_access_log_task ON context_access_log(task_id) WHERE task_id IS NOT NULL;
