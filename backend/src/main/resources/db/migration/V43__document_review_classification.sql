-- Existing reviews predate model classifications; retain them as unknown.
ALTER TABLE document_bee_suggestion
  ADD COLUMN category TEXT CHECK (category IN ('OMISSION', 'CONSISTENCY', 'READABILITY', 'REJECTION_FIX')),
  ADD COLUMN severity TEXT CHECK (severity IN ('MAJOR', 'MINOR')),
  ADD CONSTRAINT ck_document_suggestion_classification_pair
    CHECK ((category IS NULL) = (severity IS NULL));
