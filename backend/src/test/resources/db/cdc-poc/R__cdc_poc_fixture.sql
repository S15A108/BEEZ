-- Test-only CDC PoC fixture. Existing tables are not upgraded by IF NOT EXISTS.
-- Publication and event insert are atomic; delivery state belongs to the consumer.
CREATE TABLE IF NOT EXISTS publication_outbox (
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

-- Consumer inbox: same event can be delivered repeatedly, but is accepted once.
CREATE TABLE IF NOT EXISTS cdc_publication_inbox (
  event_id UUID PRIMARY KEY,
  project_id BIGINT NOT NULL,
  baseline_id BIGINT NOT NULL,
  publication_kind TEXT NOT NULL CHECK (publication_kind IN ('INITIAL','CHANGE')),
  status TEXT NOT NULL CHECK (status = 'PENDING'),
  payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id)
);

-- The inbox is a receipt; this separate queue is never started automatically.
CREATE TABLE IF NOT EXISTS cdc_processing_cursor (
  project_id BIGINT PRIMARY KEY REFERENCES project(id),
  initial_baseline_id BIGINT NOT NULL,
  next_version BIGINT NOT NULL CHECK (next_version > 0),
  blocked_reason TEXT CHECK (blocked_reason IS NULL OR blocked_reason IN ('late_baseline','baseline_reordered')),
  FOREIGN KEY (initial_baseline_id, project_id) REFERENCES baseline(id, project_id)
);

CREATE TABLE IF NOT EXISTS cdc_processing_job (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL,
  baseline_id BIGINT NOT NULL,
  baseline_version INT NOT NULL CHECK (baseline_version > 0),
  job_type TEXT NOT NULL DEFAULT 'PUBLISHED_SYNC' CHECK (job_type = 'PUBLISHED_SYNC'),
  source_event_id UUID REFERENCES cdc_publication_inbox(event_id),
  state TEXT NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING','RETRY_WAIT','RUNNING','SUCCEEDED','FAILED','RECONCILE')),
  attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 4),
  retry_cycle INT NOT NULL DEFAULT 0 CHECK (retry_cycle >= 0),
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  last_error_code TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id),
  UNIQUE (project_id, baseline_id, job_type),
  CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_cdc_processing_job_order ON cdc_processing_job(project_id, baseline_version, state);
