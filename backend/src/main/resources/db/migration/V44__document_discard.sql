-- 문서 등록 취소 (S15P31A108-382). 삭제하지 않고 폐기 시각만 남겨 이력을 보존한다.
-- 폐기된 문서는 같은 종류를 다시 올릴 수 있도록 프로젝트당 문서 종류 유일 인덱스에서 뺀다.
ALTER TABLE document ADD COLUMN discarded_at TIMESTAMPTZ;
DROP INDEX ux_document_project_type;
CREATE UNIQUE INDEX ux_document_project_type ON document(project_id, type) WHERE discarded_at IS NULL;
