-- S15P31A108-232: 기획 보완 질문(133)·답변 반영 설계 초안 후보(134) 저장.
-- 기존 planning_question 행은 그대로 둔다(새 컬럼은 NULL/기본값). 질문 생성은 문서 분석이 끝난 뒤 별도 단계라 분석 상태와 분리한다.

ALTER TABLE planning_question
  ADD COLUMN analysis_request_key TEXT,
  ADD COLUMN question_key TEXT,
  ADD COLUMN question_type TEXT CHECK (question_type IN ('CHOICE','SHORT_ANSWER','ESSAY')),
  ADD COLUMN answer_format JSONB,
  ADD COLUMN evidence_status TEXT CHECK (evidence_status IN ('REVIEW_CANDIDATE','UNVERIFIED')),
  ADD COLUMN summary TEXT,
  ADD COLUMN signal TEXT,
  ADD COLUMN unverified_reason TEXT,
  ADD COLUMN evidence JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(evidence) = 'array'),
  ADD COLUMN rank INT CHECK (rank IS NULL OR rank > 0),
  ADD COLUMN answer_payload JSONB;

ALTER TABLE planning_question DROP CONSTRAINT planning_question_status_check;
ALTER TABLE planning_question ADD CONSTRAINT planning_question_status_check
  CHECK (status IN ('OPEN','ANSWERED','DISMISSED','SUPERSEDED'));

-- 키는 한 분석 실행 안에서만 유일하다(133 계약 §8).
CREATE UNIQUE INDEX ux_planning_question_key
  ON planning_question(document_version_id, analysis_request_key, question_key)
  WHERE question_key IS NOT NULL;
CREATE INDEX ix_planning_question_list
  ON planning_question(project_id, document_version_id, rank, id) WHERE status <> 'SUPERSEDED';

-- 문서 버전별 질문 생성 실행. 문서 분석(document_processing)이 COMPLETED 가 된 뒤 BE 가 한 번 호출한다.
-- 실패해도 분석 상태는 바꾸지 않는다. analysis_attempt 가 분석 재시도(PLN-A02)로 늘면 다시 생성한다.
CREATE TABLE planning_question_run (
  document_version_id BIGINT PRIMARY KEY REFERENCES document_version(id),
  analysis_attempt    INT NOT NULL CHECK (analysis_attempt > 0),
  text_hash           TEXT NOT NULL,
  status              TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
  request_key         TEXT NOT NULL,
  error_code          TEXT,
  retryable           BOOLEAN NOT NULL DEFAULT false,
  lease_until         TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL))
);

-- 답변 반영 설계 초안 생성 작업(문서 버전당 1행) — PLN-A05. 입력 스냅샷은 접수 시 보존한다.
CREATE TABLE design_draft_job (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id          BIGINT NOT NULL REFERENCES project(id),
  document_version_id BIGINT NOT NULL REFERENCES document_version(id),
  requested_by        BIGINT NOT NULL REFERENCES account(id),
  request_key         TEXT NOT NULL,
  status              TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
  input_snapshot      JSONB NOT NULL,
  input_hash          TEXT NOT NULL,
  result_meta         JSONB,
  usage               JSONB NOT NULL DEFAULT '[]',
  error_code          TEXT,
  retryable           BOOLEAN NOT NULL DEFAULT false,
  lease_until         TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, requested_by, request_key, document_version_id),
  CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
  CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
-- 같은 문서 버전에서 동시에 두 작업이 돌지 않게 한다.
CREATE UNIQUE INDEX ux_design_draft_job_active ON design_draft_job(document_version_id) WHERE status IN ('PENDING','RUNNING');
CREATE INDEX ix_design_draft_job_queue ON design_draft_job(status, id);

-- 생성 후보: AI 응답을 바로 DRAFT 로 만들지 않고 불변으로 보존한다. 채택할 때만 설계 항목 DRAFT 가 만들어진다.
CREATE TABLE design_draft_candidate (
  id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  job_id                       BIGINT NOT NULL REFERENCES design_draft_job(id),
  project_id                   BIGINT NOT NULL REFERENCES project(id),
  candidate_key                TEXT NOT NULL,
  change_kind                  TEXT NOT NULL CHECK (change_kind IN ('CREATE','UPDATE')),
  kind                         TEXT NOT NULL CHECK (kind IN ('REQUIREMENT','API_CONTRACT','DATA_CONTRACT','POLICY','CONSTRAINT')),
  base_design_item_version_id  BIGINT REFERENCES design_item_version(id),
  base_status                  TEXT CHECK (base_status IN ('DRAFT','APPROVED')),
  before_content               JSONB,
  before_hash                  TEXT,
  proposed_after               JSONB NOT NULL,
  changed_fields               JSONB NOT NULL DEFAULT '[]',
  conflicting_fields           JSONB NOT NULL DEFAULT '[]',
  sources                      JSONB NOT NULL DEFAULT '[]',
  answer_refs                  JSONB NOT NULL DEFAULT '[]',
  support                      TEXT NOT NULL CHECK (support IN ('DOCUMENT','ANSWER','DOCUMENT_AND_ANSWER','NONE')),
  rationale                    TEXT,
  status                       TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ADOPTED','DISCARDED')),
  adopted_item_version_id      BIGINT REFERENCES design_item_version(id),
  decided_by                   BIGINT REFERENCES account(id),
  decided_at                   TIMESTAMPTZ,
  created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (job_id, candidate_key),
  CHECK ((change_kind = 'UPDATE') = (base_design_item_version_id IS NOT NULL)),
  CHECK ((status = 'ADOPTED') = (adopted_item_version_id IS NOT NULL))
);
CREATE INDEX ix_design_draft_candidate_job ON design_draft_candidate(job_id, id);

-- 후보 본문·근거는 보존한다. 채택 상태 컬럼만 바뀔 수 있다.
CREATE FUNCTION protect_design_draft_candidate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.job_id,NEW.project_id,NEW.candidate_key,NEW.change_kind,NEW.kind,NEW.base_design_item_version_id,NEW.base_status,
      NEW.before_content,NEW.before_hash,NEW.proposed_after,NEW.changed_fields,NEW.conflicting_fields,NEW.sources,NEW.answer_refs,
      NEW.support,NEW.rationale)
     IS DISTINCT FROM
     (OLD.job_id,OLD.project_id,OLD.candidate_key,OLD.change_kind,OLD.kind,OLD.base_design_item_version_id,OLD.base_status,
      OLD.before_content,OLD.before_hash,OLD.proposed_after,OLD.changed_fields,OLD.conflicting_fields,OLD.sources,OLD.answer_refs,
      OLD.support,OLD.rationale) THEN
    RAISE EXCEPTION '생성 후보의 본문과 근거는 변경할 수 없습니다.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_design_draft_candidate_immutable BEFORE UPDATE ON design_draft_candidate
  FOR EACH ROW EXECUTE FUNCTION protect_design_draft_candidate();
