-- Retain only a hash of each request key. Questions and responses are not stored.
-- Uniqueness covers concurrent requests across servers and survives restarts.
CREATE TABLE bee_question_request_key (
  project_id BIGINT NOT NULL REFERENCES project(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  key_hash CHAR(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, account_id, key_hash)
);
