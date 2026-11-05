-- 활동 이벤트 저장 (S15P31A108-182, 계약 10-notification-activity-contract.md 4·7절).
-- V29(#123)·V30(#127) 이 열린 PR 에 있어 V31 을 쓴다. outOfOrder 가 꺼져 있으므로 그 PR 들이 먼저 머지돼야 한다.
-- 아직 activity_event 에 쓰는 코드가 없어(빈 테이블) 아래 CHECK 를 바로 건다.

-- 비비(BEE) 행위자 추가. V2 의 인라인 CHECK 는 PostgreSQL 기본 이름 activity_event_actor_kind_check 다.
ALTER TABLE activity_event DROP CONSTRAINT activity_event_actor_kind_check;
ALTER TABLE activity_event ADD CONSTRAINT activity_event_actor_kind_check
  CHECK (actor_kind IN ('HUMAN','AGENT','BEE','SYSTEM'));

-- actor_id 는 행위 계정(에이전트면 토큰 주인). 사람·에이전트만 있고 비비·시스템은 비운다.
ALTER TABLE activity_event ADD CONSTRAINT ck_activity_actor_account
  CHECK ((actor_kind IN ('HUMAN','AGENT')) = (actor_id IS NOT NULL));

-- 보조 참조(필터용)와 결과. 보조 참조는 같은 프로젝트의 행만 가리키게 (id, project_id) 로 묶는다.
ALTER TABLE activity_event
  ADD COLUMN agent_session_id   BIGINT REFERENCES agent_session(id),
  ADD COLUMN task_id            BIGINT,
  ADD COLUMN change_proposal_id BIGINT,
  ADD COLUMN baseline_id        BIGINT,
  ADD COLUMN result             TEXT NOT NULL DEFAULT 'SUCCESS' CHECK (result IN ('SUCCESS','FAILURE')),
  ADD CONSTRAINT fk_activity_task            FOREIGN KEY (task_id, project_id)            REFERENCES task(id, project_id),
  ADD CONSTRAINT fk_activity_change_proposal FOREIGN KEY (change_proposal_id, project_id) REFERENCES change_proposal(id, project_id),
  ADD CONSTRAINT fk_activity_baseline        FOREIGN KEY (baseline_id, project_id)        REFERENCES baseline(id, project_id),
  ADD CONSTRAINT ck_activity_agent_session   CHECK (agent_session_id IS NULL OR actor_kind = 'AGENT');

-- 실제 저장 시각. now() 는 트랜잭션 시작 시각이다. 입구는 clock_timestamp() 를 직접 넣고, 기본값도 맞춘다.
ALTER TABLE activity_event ALTER COLUMN occurred_at SET DEFAULT clock_timestamp();

-- 정렬·필터 인덱스(id 내림차순 cursor, change_proposal_id 등)는 조회 쿼리를 정하는 -183 에서 함께 바꾼다.
