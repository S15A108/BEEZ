-- 에이전트 연결 세션 기록 (S15P31A108-178).
-- 번호: dev 가 V39 까지이고, 열린 PR #178 V40 · #148 V41 다음이라 V42 (11-06 확인).
--
-- agent_session 은 V2 부터 있었지만 쓰는 코드가 없었다. -178 부터 MCP initialize 가 성공할 때마다 한 행을 만든다.
--   session_key    : MCP 세션 ID (Mcp-Session-Id). 계정·토큰과 다른 식별자다 (02-mcp-tools "연결과 인증")
--   client_name·client_version : initialize 의 clientInfo — 어떤 클라이언트인지 (예: claude-code). 클라이언트가 보낸 값 그대로
--   ended_at·end_reason : 연결이 끝난 시각과 이유. CLOSED = 클라이언트가 DELETE /mcp 로 닫음,
--                         REPLACED = 같은 계정이 같은 프로젝트에 새로 연결해 밀려남 (1인 1에이전트, Q06)
--   last_report_at : 마지막 통신 시각 (AgentConnection.last_report_at). 1분 단위로 갱신한다
-- 지금까지 행이 없어 채울 값이 없다.

ALTER TABLE agent_session
  ADD COLUMN client_name    TEXT,
  ADD COLUMN client_version TEXT,
  ADD COLUMN ended_at       TIMESTAMPTZ,
  ADD COLUMN end_reason     TEXT,
  ADD CONSTRAINT ck_agent_session_end
    CHECK ((ended_at IS NULL AND end_reason IS NULL)
        OR (ended_at IS NOT NULL AND end_reason IN ('CLOSED', 'REPLACED')));

-- 요청마다 세션 ID 로 찾는다. SDK 가 만드는 세션 ID 는 UUID 라 토큰을 넘어서도 겹치지 않는다.
-- 기존 UNIQUE (agent_token_id, session_key) 는 그대로 둔다
CREATE UNIQUE INDEX ux_agent_session_key ON agent_session (session_key);

-- 계정·프로젝트의 살아 있는 세션을 찾을 때 (중복 연결 처리) 토큰 쪽에서 들어온다
CREATE INDEX ix_agent_session_live ON agent_session (agent_token_id) WHERE ended_at IS NULL;
