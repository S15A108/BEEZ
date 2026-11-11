-- S15P31A108-389: 승인 문서만으로 설계 항목 후보를 자동 생성한다(질문·답변 없이).
-- 문서 버전마다 작업(design_draft_job) 하나가 절 단위 부분(design_draft_job_part)으로 나뉘어 실행되고,
-- 실패하면 실패한 부분만 다시 시도한다. 후보는 기존 design_draft_candidate 를 그대로 쓴다.

ALTER TABLE design_draft_job
  ADD COLUMN mode         TEXT NOT NULL DEFAULT 'WITH_ANSWERS' CHECK (mode IN ('WITH_ANSWERS','DOCUMENT_ONLY')),
  ADD COLUMN trigger_kind TEXT NOT NULL DEFAULT 'MANUAL' CHECK (trigger_kind IN ('AUTO','MANUAL')),
  ADD COLUMN attempt_no   INT  NOT NULL DEFAULT 1 CHECK (attempt_no >= 1);

-- 문서 버전마다 자동 접수는 한 번만. 동시에 두 번 접수해도 DB 가 한 건만 받는다.
CREATE UNIQUE INDEX ux_design_draft_job_auto ON design_draft_job(document_version_id) WHERE trigger_kind = 'AUTO';

CREATE TABLE design_draft_job_part (
  job_id      BIGINT NOT NULL REFERENCES design_draft_job(id),
  part_no     INT    NOT NULL CHECK (part_no >= 1),
  start_line  INT    NOT NULL CHECK (start_line >= 1),
  end_line    INT    NOT NULL,
  status      TEXT   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','FAILED')),
  error_code  TEXT,
  retryable   BOOLEAN NOT NULL DEFAULT false,
  usage       JSONB  NOT NULL DEFAULT '[]',
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (job_id, part_no),
  CHECK (end_line >= start_line),
  CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);

-- 후보 중복 표시: 같은 지문의 기존 설계 항목·이미 채택한 후보·이미 폐기한 후보가 있으면 구분해서 보여 준다.
ALTER TABLE design_draft_candidate
  ADD COLUMN part_no      INT,
  ADD COLUMN fingerprint  TEXT,
  ADD COLUMN overlap_kind TEXT CHECK (overlap_kind IN ('EXISTING_ITEM','ADOPTED_CANDIDATE','DISCARDED_CANDIDATE','PENDING_CANDIDATE')),
  ADD COLUMN overlap_ref  BIGINT,
  ADD CHECK ((overlap_kind IS NULL) = (overlap_ref IS NULL));
CREATE INDEX ix_design_draft_candidate_fingerprint ON design_draft_candidate(project_id, fingerprint) WHERE fingerprint IS NOT NULL;

-- 불변 보호에 새 컬럼도 포함한다(채택 상태 컬럼만 바뀔 수 있다).
CREATE OR REPLACE FUNCTION protect_design_draft_candidate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.job_id,NEW.project_id,NEW.candidate_key,NEW.change_kind,NEW.kind,NEW.base_design_item_version_id,NEW.base_status,
      NEW.before_content,NEW.before_hash,NEW.proposed_after,NEW.changed_fields,NEW.conflicting_fields,NEW.sources,NEW.answer_refs,
      NEW.support,NEW.rationale,NEW.part_no,NEW.fingerprint,NEW.overlap_kind,NEW.overlap_ref)
     IS DISTINCT FROM
     (OLD.job_id,OLD.project_id,OLD.candidate_key,OLD.change_kind,OLD.kind,OLD.base_design_item_version_id,OLD.base_status,
      OLD.before_content,OLD.before_hash,OLD.proposed_after,OLD.changed_fields,OLD.conflicting_fields,OLD.sources,OLD.answer_refs,
      OLD.support,OLD.rationale,OLD.part_no,OLD.fingerprint,OLD.overlap_kind,OLD.overlap_ref) THEN
    RAISE EXCEPTION '생성 후보의 본문과 근거는 변경할 수 없습니다.';
  END IF;
  RETURN NEW;
END $$;
