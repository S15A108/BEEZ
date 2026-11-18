CREATE TABLE meeting_revision_draft (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL,
  meeting_id BIGINT NOT NULL,
  summary_id BIGINT NOT NULL,
  created_by BIGINT NOT NULL REFERENCES account(id),
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (meeting_id, project_id) REFERENCES meeting(id, project_id),
  FOREIGN KEY (summary_id, meeting_id) REFERENCES meeting_summary(id, meeting_id)
);
CREATE INDEX ix_meeting_revision_draft_meeting ON meeting_revision_draft(meeting_id, id DESC);
