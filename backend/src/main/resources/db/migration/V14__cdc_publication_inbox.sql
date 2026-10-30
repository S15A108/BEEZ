-- Consumer inbox: same event can be delivered repeatedly, but is accepted once.
CREATE TABLE cdc_publication_inbox (
  event_id UUID PRIMARY KEY,
  project_id BIGINT NOT NULL,
  baseline_id BIGINT NOT NULL,
  publication_kind TEXT NOT NULL CHECK (publication_kind IN ('INITIAL','CHANGE')),
  status TEXT NOT NULL CHECK (status = 'PENDING'),
  payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id)
);
