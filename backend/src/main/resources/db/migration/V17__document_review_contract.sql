-- V16 belongs to validation_finding_check. Document review changes start at V17.
-- Existing reviews retain their original input/result/hash. Missing v2 stages require a new review, not a fabricated success.
ALTER TABLE document_bee_suggestion
  ADD COLUMN type TEXT NOT NULL DEFAULT 'PATCH' CHECK (type IN ('PATCH','COMMENT')),
  ADD COLUMN step TEXT CHECK (step IN ('BODY','API_SPEC','ERD','REQ_SPEC')),
  ADD COLUMN sources JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(sources)='array'),
  ALTER COLUMN original_text DROP NOT NULL,
  ALTER COLUMN replacement_text DROP NOT NULL,
  ALTER COLUMN decision DROP NOT NULL;

ALTER TABLE document_bee_suggestion ADD CONSTRAINT ck_document_suggestion_type CHECK (
  (type='PATCH' AND original_text IS NOT NULL AND length(original_text)>0
    AND replacement_text IS NOT NULL AND decision IS NOT NULL)
  OR
  (type='COMMENT' AND original_text IS NULL AND replacement_text IS NULL
    AND decision IS NULL AND decided_at IS NULL)
);

CREATE FUNCTION protect_submitted_bee_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM document_review_submission WHERE bee_review_id=OLD.id) THEN
    RAISE EXCEPTION '제출한 검토 자료는 변경할 수 없습니다.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_submitted_bee_review BEFORE UPDATE ON document_bee_review
  FOR EACH ROW EXECUTE FUNCTION protect_submitted_bee_review();

CREATE FUNCTION protect_submitted_bee_suggestion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (TG_OP <> 'INSERT' AND EXISTS (SELECT 1 FROM document_review_submission WHERE bee_review_id=OLD.review_id))
     OR (TG_OP <> 'DELETE' AND EXISTS (SELECT 1 FROM document_review_submission WHERE bee_review_id=NEW.review_id)) THEN
    RAISE EXCEPTION '제출한 검토 의견은 변경할 수 없습니다.';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_submitted_bee_suggestion BEFORE INSERT OR UPDATE OR DELETE ON document_bee_suggestion
  FOR EACH ROW EXECUTE FUNCTION protect_submitted_bee_suggestion();
