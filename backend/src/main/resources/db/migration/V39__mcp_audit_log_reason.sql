-- S15P31A108-177 MCP 접근 제어·감사 기록.
-- 번호(V39)는 머지 직전 dev 와 열린 PR 기준으로 다시 확인한다 (dev V37, 열린 #178 이 V38).

-- 1) 실패 결과를 가르는 칸 두 개 (05 문서 참고 2 "deny_reason 칸을 -177 에서 추가").
--    deny_reason: result = DENIED 일 때 서버만 아는 거부 사유의 종류(not_member, cross_project 등). 번호는 넣지 않는다.
--    error_code : 실패면 응답의 error.code (not_found, validation_failed 등). 성공이면 NULL.
ALTER TABLE mcp_audit_log
  ADD COLUMN deny_reason TEXT,
  ADD COLUMN error_code  TEXT,
  ADD CONSTRAINT ck_mcp_audit_log_deny_reason CHECK ((result = 'DENIED') = (deny_reason IS NOT NULL)),
  ADD CONSTRAINT ck_mcp_audit_log_error_code  CHECK ((result = 'OK') = (error_code IS NULL));

-- 2) 감사 기록은 고치지 않는다 (append-only). context_access_log(V5)와 같은 트리거.
CREATE TRIGGER trg_mcp_audit_log_immutable BEFORE UPDATE ON mcp_audit_log
  FOR EACH ROW EXECUTE FUNCTION forbid_update();
