-- Approval-time results and publication-time intake have independent lifecycles.
CREATE TABLE change_impact_run (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  change_proposal_id BIGINT NOT NULL,
  revision_no INT NOT NULL,
  snapshot_hash TEXT NOT NULL,
  base_baseline_id BIGINT NOT NULL,
  relation_set_revision BIGINT NOT NULL,
  input_fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('RUNNING','COMPLETED','INSUFFICIENT_EVIDENCE','NOT_EVALUATED','FAILED','STALE')),
  error_code TEXT,
  explicit_result JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(explicit_result)='array'),
  ai_result JSONB,
  generation JSONB,
  usage JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(usage)='array'),
  usage_status TEXT NOT NULL CHECK (usage_status IN ('REPORTED','UNKNOWN','NOT_STARTED')),
  request_key TEXT NOT NULL UNIQUE,
  requested_by BIGINT NOT NULL REFERENCES account(id),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  lease_until TIMESTAMPTZ NOT NULL,
  FOREIGN KEY (change_proposal_id,project_id) REFERENCES change_proposal(id,project_id),
  FOREIGN KEY (change_proposal_id,revision_no) REFERENCES change_proposal_revision(change_proposal_id,revision_no),
  FOREIGN KEY (base_baseline_id,project_id) REFERENCES baseline(id,project_id)
);
CREATE UNIQUE INDEX uq_impact_running ON change_impact_run(change_proposal_id,revision_no) WHERE status='RUNNING';
CREATE INDEX ix_impact_latest ON change_impact_run(change_proposal_id,revision_no,finished_at DESC,id DESC);

CREATE TABLE published_change_analysis (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  published_baseline_id BIGINT NOT NULL,
  change_proposal_id BIGINT,
  status TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','NOT_EVALUATED')),
  attempt INT NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 4),
  lease_until TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ,
  error_code TEXT CHECK (error_code IS NULL OR error_code ~ '^[a-z][a-z0-9_]{0,63}$'),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  UNIQUE(project_id,published_baseline_id),
  FOREIGN KEY (published_baseline_id,project_id) REFERENCES baseline(id,project_id),
  FOREIGN KEY (change_proposal_id,project_id) REFERENCES change_proposal(id,project_id),
  CHECK (status<>'RUNNING' OR (attempt>0 AND lease_until IS NOT NULL))
);
CREATE INDEX ix_published_analysis_recovery ON published_change_analysis(id) WHERE status IN ('PENDING','RUNNING');
INSERT INTO published_change_analysis(project_id,published_baseline_id,change_proposal_id,status,error_code,finished_at)
SELECT project_id,id,change_proposal_id,'NOT_EVALUATED','published_before_intake',clock_timestamp() FROM baseline;
