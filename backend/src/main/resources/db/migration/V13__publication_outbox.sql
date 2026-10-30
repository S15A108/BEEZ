-- Publication and event insert are atomic; delivery state belongs to the consumer.
CREATE TABLE publication_outbox (
  event_id UUID PRIMARY KEY,
  event_type TEXT NOT NULL CHECK (event_type = 'DESIGN_PUBLISHED'),
  event_version INT NOT NULL CHECK (event_version > 0),
  project_id BIGINT NOT NULL,
  baseline_id BIGINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL,
  FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id),
  UNIQUE (baseline_id, event_type),
  CHECK (jsonb_typeof(payload) = 'object')
);
