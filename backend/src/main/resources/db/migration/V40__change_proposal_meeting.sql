-- 변경 제안의 회의 출처 (S15P31A108-297, MTG-A13). 회의 끝내기가 만든 제안만 채운다. 공개 CHG-A01 은 받지 않는다(Q24).
-- 스냅샷 해시·멱등 지문에는 넣지 않는다 — 출처는 제안 내용이 아니다.
ALTER TABLE change_proposal ADD COLUMN meeting_id BIGINT;
ALTER TABLE change_proposal
    ADD CONSTRAINT fk_change_proposal_meeting FOREIGN KEY (meeting_id, project_id) REFERENCES meeting (id, project_id);
-- 회의당 살아 있는(거두지 않은) 제안은 1건. 거두면 같은 회의로 새 제안을 만들 수 있다.
CREATE UNIQUE INDEX ux_change_proposal_meeting_live ON change_proposal (meeting_id)
    WHERE meeting_id IS NOT NULL AND status <> 'WITHDRAWN';
