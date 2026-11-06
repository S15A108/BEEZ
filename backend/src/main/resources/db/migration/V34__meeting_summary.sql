-- 비비 회의 정리(MTG-A11)·정리 수정 요청(MTG-A12) 저장 (S15P31A108-235)
-- 정리는 고치거나 지우지 않는다. 수정 요청은 새 행이고 이전 정리는 previous_summary_id 로 이어진다.
-- 회의별 "최신 정리" = max(id).

-- 같은 회의의 기록만 정리가 가리키게 하려고 (기록, 회의) 쌍을 복합 FK 의 대상으로 쓴다.
ALTER TABLE meeting_entry ADD CONSTRAINT uq_meeting_entry_meeting UNIQUE (id, meeting_id);

CREATE TABLE meeting_summary (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id          BIGINT NOT NULL,
  project_id          BIGINT NOT NULL,
  entry_id            BIGINT NOT NULL UNIQUE,   -- 회의 기록에 남긴 SUMMARY 기록
  through_entry_id    BIGINT NOT NULL,          -- 정리에 쓴 마지막 기록
  previous_summary_id BIGINT,
  feedback            TEXT,
  result              JSONB NOT NULL,           -- 검증·정규화한 conflicts, decisions, document_changes, action_items
  generation          JSONB NOT NULL,           -- origin, model, text, sources, unresolved, usage
  created_by          BIGINT NOT NULL REFERENCES account(id),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (id, meeting_id),
  FOREIGN KEY (meeting_id, project_id) REFERENCES meeting(id, project_id),
  FOREIGN KEY (entry_id, meeting_id) REFERENCES meeting_entry(id, meeting_id),
  FOREIGN KEY (through_entry_id, meeting_id) REFERENCES meeting_entry(id, meeting_id),
  FOREIGN KEY (previous_summary_id, meeting_id) REFERENCES meeting_summary(id, meeting_id),
  CHECK (previous_summary_id IS NULL OR previous_summary_id < id)
);
CREATE INDEX ix_meeting_summary_meeting ON meeting_summary(meeting_id, id DESC);
