-- Initial design commands have their own scope; do not overload document_http_command.
CREATE TABLE design_http_command (
  project_id BIGINT NOT NULL REFERENCES project(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  operation TEXT NOT NULL,
  request_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  response JSONB NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
  PRIMARY KEY (project_id, account_id, operation, request_key)
);
