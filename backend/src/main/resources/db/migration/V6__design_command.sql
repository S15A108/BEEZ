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

-- A working copy is separate from immutable submissions and document approval.
CREATE TABLE document_original_cleanup (
  storage_key TEXT PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE document_working_copy (
  document_id BIGINT PRIMARY KEY REFERENCES document(id),
  owner_account_id BIGINT NOT NULL REFERENCES account(id),
  initial_version_id BIGINT NOT NULL REFERENCES document_version(id),
  base_version_id BIGINT REFERENCES document_version(id),
  revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
  status TEXT NOT NULL DEFAULT 'EDITING' CHECK (status IN ('EDITING','SUBMITTED','REJECTED','APPROVED')),
  content_text TEXT NOT NULL CHECK (length(btrim(content_text)) > 0),
  file_name TEXT NOT NULL,
  storage_key TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE document_bee_review (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES document_working_copy(document_id),
  input_revision BIGINT NOT NULL,
  resolved_revision BIGINT NOT NULL,
  reference_hash TEXT NOT NULL,
  input JSONB NOT NULL,
  result JSONB NOT NULL,
  usage JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(id, document_id)
);
CREATE TABLE document_bee_suggestion (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  review_id BIGINT NOT NULL REFERENCES document_bee_review(id),
  explanation TEXT NOT NULL,
  original_text TEXT NOT NULL CHECK (length(original_text) > 0),
  replacement_text TEXT NOT NULL,
  decision TEXT NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','APPLIED','SKIPPED')),
  decided_at TIMESTAMPTZ
);
CREATE TABLE document_bee_review_request (
  document_id BIGINT NOT NULL REFERENCES document_working_copy(document_id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  request_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  review_id BIGINT REFERENCES document_bee_review(id),
  error_code TEXT,
  usage JSONB NOT NULL DEFAULT '[]',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(document_id,account_id,request_key)
);
CREATE TABLE document_review_submission (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES document_working_copy(document_id),
  document_version_id BIGINT NOT NULL UNIQUE REFERENCES document_version(id),
  submitted_by BIGINT NOT NULL REFERENCES account(id),
  revision BIGINT NOT NULL,
  bee_review_id BIGINT NOT NULL,
  content_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
  decided_by BIGINT REFERENCES account(id),
  rejection_reason TEXT,
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at TIMESTAMPTZ,
  FOREIGN KEY (bee_review_id,document_id) REFERENCES document_bee_review(id,document_id),
  CHECK ((status='SUBMITTED') = (decided_at IS NULL AND decided_by IS NULL)),
  CHECK (status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0)),
  UNIQUE(document_id,revision)
);
CREATE UNIQUE INDEX ux_document_pending_submission ON document_review_submission(document_id) WHERE status='SUBMITTED';
-- 문서 검토 알림은 공통 알림 서비스 연동 후 제공한다. 문서 도메인은 notification에 직접 저장하지 않는다.
CREATE FUNCTION protect_document_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'SUBMITTED' OR
     (NEW.document_id,NEW.document_version_id,NEW.submitted_by,NEW.revision,NEW.bee_review_id,NEW.content_hash,NEW.submitted_at)
       IS DISTINCT FROM
     (OLD.document_id,OLD.document_version_id,OLD.submitted_by,OLD.revision,OLD.bee_review_id,OLD.content_hash,OLD.submitted_at) THEN
    RAISE EXCEPTION '제출 이력은 변경할 수 없습니다.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_document_submission_immutable BEFORE UPDATE ON document_review_submission
  FOR EACH ROW EXECUTE FUNCTION protect_document_submission();

CREATE FUNCTION protect_submitted_document_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM document_review_submission WHERE document_version_id=OLD.id) AND
     (NEW.document_id,NEW.version_no,NEW.storage_key,NEW.content_hash,NEW.content_text,NEW.created_by,NEW.parent_version_id)
       IS DISTINCT FROM
     (OLD.document_id,OLD.version_no,OLD.storage_key,OLD.content_hash,OLD.content_text,OLD.created_by,OLD.parent_version_id) THEN
    RAISE EXCEPTION '제출한 문서 내용은 변경할 수 없습니다.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_submitted_document_content BEFORE UPDATE ON document_version
  FOR EACH ROW EXECUTE FUNCTION protect_submitted_document_content();

CREATE FUNCTION protect_pending_working_copy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='SUBMITTED' AND
     (NEW.content_text,NEW.file_name,NEW.revision,NEW.owner_account_id,NEW.initial_version_id,NEW.document_id,NEW.storage_key)
       IS DISTINCT FROM
     (OLD.content_text,OLD.file_name,OLD.revision,OLD.owner_account_id,OLD.initial_version_id,OLD.document_id,OLD.storage_key) THEN
    RAISE EXCEPTION '팀장 검토를 요청한 작성 내용은 잠겨 있습니다.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_pending_working_copy BEFORE UPDATE ON document_working_copy
  FOR EACH ROW EXECUTE FUNCTION protect_pending_working_copy();
