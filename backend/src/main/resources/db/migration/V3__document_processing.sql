-- Document-only durable jobs, active indexes and HTTP idempotency. V2 remains unchanged.
CREATE UNIQUE INDEX ux_document_project_type ON document(project_id, type);
CREATE TABLE document_processing (
  document_version_id BIGINT PRIMARY KEY REFERENCES document_version(id),
  attempt INT NOT NULL DEFAULT 1 CHECK (attempt > 0),
  automatic_recoveries INT NOT NULL DEFAULT 0 CHECK (automatic_recoveries BETWEEN 0 AND 2),
  status TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
  source_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (source_status IN ('PENDING','RUNNING','READY','FAILED')),
  index_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (index_status IN ('PENDING','RUNNING','READY','FAILED')),
  text_hash TEXT NOT NULL,
  error_code TEXT,
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_document_processing_queue ON document_processing(status, updated_at);

CREATE TABLE document_index_manifest (
  document_version_id BIGINT PRIMARY KEY REFERENCES document_version(id),
  attempt INT NOT NULL,
  text_hash TEXT NOT NULL,
  embedding_profile_id TEXT NOT NULL,
  chunking_profile_id TEXT NOT NULL,
  parser_version TEXT NOT NULL,
  chunk_count INT NOT NULL CHECK (chunk_count > 0),
  published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_http_command (
  project_id BIGINT NOT NULL REFERENCES project(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  operation TEXT NOT NULL,
  request_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  response JSONB NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
  PRIMARY KEY (project_id, account_id, operation, request_key)
);
