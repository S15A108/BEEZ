-- 회의 모델을 비비 충돌 회의(MTG-A02~A14, S15P31A108-205)에 맞춘다.
-- 기존 ai_summary·ai_origin·참여 상태 INVITED/DECLINED 는 폐지 예정 API 의 흔적이라 건드리지 않고 쓰지 않는다.

ALTER TABLE meeting
    ALTER COLUMN created_by DROP NOT NULL,                       -- 비비가 연 회의는 사람 계정이 없다
    ADD COLUMN origin TEXT NOT NULL DEFAULT 'BEE_CONFLICT' CHECK (origin IN ('BEE_CONFLICT', 'HUMAN')),
    ADD COLUMN conflict_summary TEXT,
    ADD COLUMN task_set_key TEXT NOT NULL DEFAULT '',            -- 충돌 작업 ID 를 오름차순으로 이은 키(예 12,15)
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN closed_by BIGINT REFERENCES account(id),
    ADD CONSTRAINT ck_meeting_closed CHECK (status = 'OPEN' OR closed_at IS NOT NULL);
ALTER TABLE meeting ALTER COLUMN task_set_key DROP DEFAULT;

-- 같은 작업 묶음에 열린 회의는 하나만
CREATE UNIQUE INDEX ux_meeting_open_task_set ON meeting(project_id, task_set_key) WHERE status = 'OPEN';

CREATE TABLE meeting_task (
    meeting_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    task_id    BIGINT NOT NULL,
    PRIMARY KEY (meeting_id, task_id),
    FOREIGN KEY (meeting_id, project_id) REFERENCES meeting(id, project_id),
    FOREIGN KEY (task_id, project_id) REFERENCES task(id, project_id)
);
CREATE INDEX ix_meeting_task_task ON meeting_task(task_id);

ALTER TABLE meeting_participant
    ADD COLUMN role TEXT CHECK (role IN ('LEADER', 'TASK_OWNER', 'DOMAIN_OWNER'));

ALTER TABLE meeting_entry
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'
        CHECK (kind IN ('TEXT', 'BEE_ANALYSIS', 'MINUTES', 'CONFLICT_BRIEF', 'SUMMARY')),
    ADD COLUMN refs JSONB;
CREATE INDEX ix_meeting_entry_meeting ON meeting_entry(meeting_id, id);
