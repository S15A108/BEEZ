-- Preserve the exact AI source ranges without inventing them for old indexes.
ALTER TABLE document_chunk
  ADD COLUMN chunk_key TEXT,
  ADD COLUMN source_spans JSONB,
  ADD COLUMN context_refs JSONB,
  ADD CONSTRAINT ck_document_chunk_source_metadata CHECK (
    (chunk_key IS NULL AND source_spans IS NULL AND context_refs IS NULL)
    OR
    (chunk_key IS NOT NULL AND source_spans IS NOT NULL AND context_refs IS NOT NULL
      AND jsonb_typeof(source_spans) = 'array' AND jsonb_array_length(source_spans) > 0
      AND jsonb_typeof(context_refs) = 'array')
  );

CREATE UNIQUE INDEX ux_document_chunk_version_key
  ON document_chunk(document_version_id, chunk_key) WHERE chunk_key IS NOT NULL;
