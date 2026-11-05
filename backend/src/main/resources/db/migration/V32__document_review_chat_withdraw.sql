-- S15P31A108-231: 비비 보완 제안 대화(DOC-A22)·직접 요청(DOC-A23)·팀장 검토 요청 취소(DOC-A24).
-- 제출한 검토 자료는 V17 의 트리거가 이미 잠가 두었다. 취소해도 그 검토·제안·대화를 지우거나 바꾸지 않는다(이력 보존).

-- 취소한 제출은 WITHDRAWN 으로 종결한다. 결정(승인·반려)과 구분하려고 취소 시각·취소자를 따로 둔다.
ALTER TABLE document_review_submission
  ADD COLUMN withdrawn_at TIMESTAMPTZ,
  ADD COLUMN withdrawn_by BIGINT REFERENCES account(id);

-- 기존 상태·결정 CHECK 는 SUBMITTED/APPROVED/REJECTED 만 안다. 이름이 자동 생성이라 정의로 찾아 교체한다(반려 사유 CHECK 는 건드리지 않는다).
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'document_review_submission'::regclass AND contype = 'c'
              AND (pg_get_constraintdef(oid) LIKE '%SUBMITTED%' OR pg_get_constraintdef(oid) LIKE '%decided_at%') LOOP
    EXECUTE format('ALTER TABLE document_review_submission DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

ALTER TABLE document_review_submission ADD CONSTRAINT ck_document_submission_status
  CHECK (status IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN'));
ALTER TABLE document_review_submission ADD CONSTRAINT ck_document_submission_closing CHECK (
  (status = 'SUBMITTED' AND decided_at IS NULL AND decided_by IS NULL AND withdrawn_at IS NULL AND withdrawn_by IS NULL)
  OR (status IN ('APPROVED','REJECTED') AND (decided_at IS NOT NULL OR decided_by IS NOT NULL)
      AND withdrawn_at IS NULL AND withdrawn_by IS NULL)
  OR (status = 'WITHDRAWN' AND decided_at IS NULL AND decided_by IS NULL AND withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL));

-- 보완 제안 출처와, 비비 대화에서 받은 다른 대체문으로 반영했을 때 실제로 쓴 문장.
ALTER TABLE document_bee_suggestion
  ADD COLUMN origin TEXT NOT NULL DEFAULT 'REVIEW' CHECK (origin IN ('REVIEW','DIRECT_REQUEST')),
  ADD COLUMN applied_replacement_text TEXT;
ALTER TABLE document_bee_suggestion ADD CONSTRAINT ck_document_suggestion_applied_text
  CHECK (applied_replacement_text IS NULL OR (type = 'PATCH' AND decision = 'APPLIED'));

-- 제안 카드별 비비와의 대화. 검토 단위로 보존해 DOC-A13·A20 의 검토 결과와 함께 조회한다(Q13 기본안: 창을 닫아도 남는다).
CREATE TABLE document_bee_message (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  review_id         BIGINT NOT NULL REFERENCES document_bee_review(id),
  suggestion_id     BIGINT NOT NULL REFERENCES document_bee_suggestion(id),
  author_kind       TEXT NOT NULL CHECK (author_kind IN ('HUMAN','BEE')),
  author_account_id BIGINT REFERENCES account(id),
  body              TEXT NOT NULL CHECK (length(btrim(body)) > 0),
  proposed_text     TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((author_kind = 'HUMAN') = (author_account_id IS NOT NULL)),
  CHECK (author_kind = 'BEE' OR proposed_text IS NULL)
);
CREATE INDEX ix_document_bee_message_suggestion ON document_bee_message(suggestion_id, id);
CREATE INDEX ix_document_bee_message_review ON document_bee_message(review_id, id);

-- 제출에 쓰인 검토의 대화는 바꾸거나 지우지 않는다(제안과 같은 규칙, V17).
CREATE FUNCTION protect_submitted_bee_message() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (TG_OP <> 'INSERT' AND EXISTS (SELECT 1 FROM document_review_submission WHERE bee_review_id=OLD.review_id))
     OR (TG_OP <> 'DELETE' AND EXISTS (SELECT 1 FROM document_review_submission WHERE bee_review_id=NEW.review_id)) THEN
    RAISE EXCEPTION '제출한 검토의 대화는 변경할 수 없습니다.';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_submitted_bee_message BEFORE INSERT OR UPDATE OR DELETE ON document_bee_message
  FOR EACH ROW EXECUTE FUNCTION protect_submitted_bee_message();
