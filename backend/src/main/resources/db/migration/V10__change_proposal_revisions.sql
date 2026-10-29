-- #195. User-assigned V10; verify preceding PR migration numbers/application order before deployment.
ALTER TABLE change_proposal ADD COLUMN title TEXT;
UPDATE change_proposal SET title=key;
ALTER TABLE change_proposal ALTER COLUMN title SET NOT NULL;
ALTER TABLE change_proposal ALTER COLUMN title SET DEFAULT '';
ALTER TABLE change_proposal ADD COLUMN revision_no INT NOT NULL DEFAULT 1 CHECK (revision_no > 0);
ALTER TABLE change_proposal DROP CONSTRAINT change_proposal_status_check;
ALTER TABLE change_proposal ADD CONSTRAINT change_proposal_status_check
  CHECK (status IN ('PROPOSED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','WITHDRAWN'));
ALTER TABLE change_proposal ADD COLUMN previous_proposal_id BIGINT;
ALTER TABLE change_proposal ADD CONSTRAINT fk_proposal_previous_project
  FOREIGN KEY (previous_proposal_id,project_id) REFERENCES change_proposal(id,project_id);
ALTER TABLE change_proposal ADD CONSTRAINT ck_proposal_previous_not_self CHECK (previous_proposal_id<>id);
ALTER TABLE change_proposal ADD COLUMN withdrawn_by BIGINT REFERENCES account(id);
ALTER TABLE change_proposal ADD COLUMN withdrawn_at TIMESTAMPTZ;
ALTER TABLE change_proposal ADD CONSTRAINT ck_proposal_withdrawal CHECK (
  (status='WITHDRAWN' AND withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL AND withdrawn_by=proposer_account_id)
  OR (status<>'WITHDRAWN' AND withdrawn_by IS NULL AND withdrawn_at IS NULL)
);
ALTER TABLE change_proposal_item ADD COLUMN schema_version INT CHECK (schema_version > 0);

CREATE TABLE change_proposal_revision (
  change_proposal_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  revision_no INT NOT NULL CHECK (revision_no > 0),
  base_baseline_id BIGINT NOT NULL,
  snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
  snapshot_hash TEXT NOT NULL CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
  submitted_by BIGINT NOT NULL REFERENCES account(id),
  submitted_token_id BIGINT REFERENCES agent_token(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (change_proposal_id,revision_no),
  FOREIGN KEY (change_proposal_id,project_id) REFERENCES change_proposal(id,project_id),
  FOREIGN KEY (base_baseline_id,project_id) REFERENCES baseline(id,project_id)
);
-- Preserve existing rows as legacy snapshots without guessing a proposed content schema version.
INSERT INTO change_proposal_revision(change_proposal_id,project_id,revision_no,base_baseline_id,
  snapshot,snapshot_hash,submitted_by,submitted_token_id,created_at)
SELECT c.id,c.project_id,1,c.base_baseline_id,s.value,'sha256:'||encode(sha256(convert_to(s.value::text,'UTF8')),'hex'),
  c.proposer_account_id,c.proposer_token_id,c.created_at
FROM change_proposal c CROSS JOIN LATERAL (
  SELECT jsonb_build_object('legacy',true,'input',jsonb_build_object(
    'base_baseline_id',c.base_baseline_id::text,'title',c.title,'reason',c.reason,
    'task_id',c.task_id::text,'validation_id',c.validation_id::text,
    'items',COALESCE((SELECT jsonb_agg(jsonb_build_object('operation',i.op,
      'design_item_id',i.design_item_id::text,'base_design_item_version_id',i.before_version_id::text,
      'kind',i.new_kind,'schema_version',i.schema_version,'proposed_content',i.proposed_content) ORDER BY i.id)
      FROM change_proposal_item i WHERE i.change_proposal_id=c.id),'[]'::jsonb),
    'documents','[]'::jsonb),'documents','[]'::jsonb) AS value
) s;
CREATE TRIGGER trg_proposal_revision_immutable BEFORE UPDATE OR DELETE ON change_proposal_revision
  FOR EACH ROW EXECUTE FUNCTION forbid_update();

CREATE TABLE change_proposal_document (
  change_proposal_id BIGINT NOT NULL,
  revision_no INT NOT NULL,
  project_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  base_document_version_id BIGINT NOT NULL,
  proposed_document_version_id BIGINT NOT NULL,
  PRIMARY KEY (change_proposal_id,revision_no,document_id),
  FOREIGN KEY (change_proposal_id,revision_no) REFERENCES change_proposal_revision(change_proposal_id,revision_no),
  FOREIGN KEY (change_proposal_id,project_id) REFERENCES change_proposal(id,project_id),
  FOREIGN KEY (document_id,project_id) REFERENCES document(id,project_id),
  FOREIGN KEY (base_document_version_id,document_id) REFERENCES document_version(id,document_id),
  FOREIGN KEY (proposed_document_version_id,document_id) REFERENCES document_version(id,document_id)
);
CREATE TRIGGER trg_proposal_document_immutable BEFORE UPDATE OR DELETE ON change_proposal_document
  FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- Decisions belong to a submitted revision, so resubmission retains every rejection.
ALTER TABLE approval ADD COLUMN proposal_revision_no INT NOT NULL DEFAULT 1;
-- DEFAULT 1 is only the legacy backfill. New decisions must name the reviewed revision explicitly.
ALTER TABLE approval ALTER COLUMN proposal_revision_no DROP DEFAULT;
ALTER TABLE approval DROP CONSTRAINT approval_change_proposal_id_key;
ALTER TABLE approval ADD CONSTRAINT ux_approval_revision UNIQUE(change_proposal_id,proposal_revision_no);
ALTER TABLE approval ADD CONSTRAINT fk_approval_revision FOREIGN KEY(change_proposal_id,proposal_revision_no)
  REFERENCES change_proposal_revision(change_proposal_id,revision_no);
CREATE TRIGGER trg_approval_delete_guard BEFORE DELETE ON approval
  FOR EACH ROW EXECUTE FUNCTION forbid_update();

CREATE INDEX ix_proposal_project_page ON change_proposal(project_id,id DESC);
CREATE INDEX ix_proposal_project_status_page ON change_proposal(project_id,status,id DESC);

CREATE FUNCTION protect_proposal_document_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.change_proposal_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM document d JOIN change_proposal c ON c.project_id=d.project_id
    WHERE d.id=NEW.document_id AND c.id=NEW.change_proposal_id
  ) THEN
    RAISE EXCEPTION '문서와 제안의 프로젝트가 일치해야 합니다.' USING ERRCODE='check_violation';
  END IF;
  IF OLD.change_proposal_id IS NOT NULL AND
     (NEW.document_id,NEW.version_no,NEW.storage_key,NEW.content_hash,NEW.content_text,
      NEW.created_by,NEW.parent_version_id,NEW.change_proposal_id)
     IS DISTINCT FROM
     (OLD.document_id,OLD.version_no,OLD.storage_key,OLD.content_hash,OLD.content_text,
      OLD.created_by,OLD.parent_version_id,OLD.change_proposal_id) THEN
    RAISE EXCEPTION '제안에 제출된 원문은 변경할 수 없습니다.' USING ERRCODE='check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_proposal_document_content BEFORE UPDATE ON document_version
  FOR EACH ROW EXECUTE FUNCTION protect_proposal_document_content();

CREATE FUNCTION protect_submitted_proposal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='WITHDRAWN' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION '거둔 제안은 변경할 수 없습니다. 새 제안으로 제출해 주세요.' USING ERRCODE='check_violation';
  END IF;
  IF NEW.status='WITHDRAWN' AND OLD.status NOT IN ('PROPOSED','IN_REVIEW','REJECTED','WITHDRAWN') THEN
    RAISE EXCEPTION '제출, 검토 중 또는 반려된 제안만 거둘 수 있습니다.' USING ERRCODE='check_violation';
  END IF;
  IF OLD.status='REJECTED' AND NEW.status='PROPOSED' AND NEW.revision_no<>OLD.revision_no+1 THEN
    RAISE EXCEPTION '재제출은 다음 리비전으로 저장해야 합니다.' USING ERRCODE='check_violation';
  END IF;
  IF (NEW.project_id,NEW.proposer_kind,NEW.proposer_account_id,NEW.proposer_token_id,NEW.key,NEW.previous_proposal_id)
     IS DISTINCT FROM (OLD.project_id,OLD.proposer_kind,OLD.proposer_account_id,OLD.proposer_token_id,OLD.key,OLD.previous_proposal_id) THEN
    RAISE EXCEPTION '제안의 프로젝트·등록자는 변경할 수 없습니다.' USING ERRCODE='check_violation';
  END IF;
  IF (NEW.title,NEW.reason,NEW.base_baseline_id,NEW.task_id,NEW.validation_id,NEW.revision_no)
     IS DISTINCT FROM (OLD.title,OLD.reason,OLD.base_baseline_id,OLD.task_id,OLD.validation_id,OLD.revision_no)
     AND NOT (OLD.status='REJECTED' AND NEW.status='PROPOSED' AND NEW.revision_no=OLD.revision_no+1) THEN
    RAISE EXCEPTION '반려된 제안만 새 리비전으로 재제출할 수 있습니다.' USING ERRCODE='check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_submitted_proposal BEFORE UPDATE ON change_proposal
  FOR EACH ROW EXECUTE FUNCTION protect_submitted_proposal();

CREATE FUNCTION check_previous_proposal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.previous_proposal_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM change_proposal p WHERE p.id=NEW.previous_proposal_id
      AND p.project_id=NEW.project_id AND p.proposer_account_id=NEW.proposer_account_id AND p.status='WITHDRAWN'
  ) THEN
    RAISE EXCEPTION '같은 프로젝트에서 본인이 거둔 제안만 연결할 수 있습니다.' USING ERRCODE='check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_previous_proposal BEFORE INSERT ON change_proposal
  FOR EACH ROW EXECUTE FUNCTION check_previous_proposal();
