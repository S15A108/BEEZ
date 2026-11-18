-- Publication-time results are separate from the approval-time change_impact_run.
ALTER TABLE published_change_analysis
  ADD COLUMN evaluation_status TEXT CHECK (evaluation_status IN ('COMPLETED','INSUFFICIENT_EVIDENCE','NOT_EVALUATED')),
  ADD COLUMN input_fingerprint TEXT,
  ADD COLUMN explicit_result JSONB CHECK (explicit_result IS NULL OR jsonb_typeof(explicit_result)='array'),
  ADD COLUMN ai_result JSONB,
  ADD COLUMN generation JSONB,
  ADD COLUMN usage JSONB CHECK (usage IS NULL OR jsonb_typeof(usage)='array'),
  ADD COLUMN usage_status TEXT CHECK (usage_status IN ('REPORTED','UNKNOWN','NOT_STARTED'));

-- Failed/retried calls retain their own usage and never overwrite a prior attempt.
CREATE TABLE published_analysis_attempt (
  intake_id BIGINT NOT NULL REFERENCES published_change_analysis(id),
  attempt INT NOT NULL CHECK (attempt BETWEEN 1 AND 4),
  outcome TEXT NOT NULL CHECK (outcome IN ('COMPLETED','INSUFFICIENT_EVIDENCE','NOT_EVALUATED','FAILED')),
  error_code TEXT CHECK (error_code IS NULL OR error_code ~ '^[a-z][a-z0-9_]{0,63}$'),
  usage JSONB NOT NULL CHECK (jsonb_typeof(usage)='array'),
  usage_status TEXT NOT NULL CHECK (usage_status IN ('REPORTED','UNKNOWN','NOT_STARTED')),
  finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (intake_id, attempt)
);
