-- S15P31A108-175 에이전트 토큰 발급·목록·폐기.
-- 번호(V7)는 -194(#37) 의 V6 다음이다. #37 이 먼저 머지돼야 한다 — 이 PR 이 먼저 가게 되면 머지 직전 V6 으로 내린다.

-- 1) 토큰 이름(API label)은 선택이다 (AGT-A01 Request). 이름 없이 발급하면 NULL.
ALTER TABLE agent_token ALTER COLUMN name DROP NOT NULL;

-- 2) 내 토큰 목록(AGT-A02, id 내림차순)과 발급 시 활성 토큰 수 세기(Q06)
CREATE INDEX ix_agent_token_account ON agent_token(account_id, id DESC);
