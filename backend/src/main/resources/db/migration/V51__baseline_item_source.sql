-- S15P31A108-406 문서가 바뀐 기준에서 설계 항목의 출처를 기준별로 기록한다.
--
-- 설계 항목 버전(design_item_version)의 출처 문서 버전·위치는 승인 뒤 불변이라 문서가 v1 → v2 로 발행돼도 v1 을 가리킨다.
-- 근거 수집·담당자 추천·초안 같은 읽는 쪽은 "출처 문서 버전 = 현재 기준의 문서 버전" 이어야 원문을 찾으므로 문서를 한 번 고치면
-- 그 문서에서 나온 항목이 전부 어긋난다. 항목 버전을 새로 만들면 작업 계획의 참조가 모두 낡아지므로, 항목 버전과 baseline_item 은
-- 그대로 두고 "그 기준에서의 유효 출처"만 INSERT 로 따로 기록한다.
--   - 행이 없으면 항목 버전의 출처가 그 기준의 유효 출처다(첫 발행, 문서가 바뀐 적 없는 항목, 과거 기준).
--   - REMAPPED: 옛 문서의 인용 줄이 새 문서에서 글자 그대로 있어 새 줄 위치로 옮겼다.
--   - NEEDS_CONFIRMATION: 인용 줄이 바뀌었거나 위치를 증명할 수 없어 옮기지 않았다. 출처 문서 버전만 새 버전으로 두고
--     source_locator 는 옛 값을 보존한다. 근거 수집은 이 항목을 쓸 수 없는 근거로 본다.
--   - remapped_from_document_version_id: REMAPPED 는 옮기기 전 문서 버전, NEEDS_CONFIRMATION 은 source_locator 의
--     줄 번호가 가리키는 문서 버전이다(확인 필요가 이어져도 처음 그 상태가 된 버전을 유지한다).
CREATE TABLE baseline_item_source (
  baseline_id                       BIGINT      NOT NULL,
  project_id                        BIGINT      NOT NULL,
  design_item_id                    BIGINT      NOT NULL,
  source_document_version_id        BIGINT      NOT NULL REFERENCES document_version(id),
  source_locator                    JSONB       NOT NULL,
  source_status                     TEXT        NOT NULL CHECK (source_status IN ('REMAPPED', 'NEEDS_CONFIRMATION')),
  remapped_from_document_version_id BIGINT      NOT NULL REFERENCES document_version(id),
  created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (baseline_id, design_item_id),
  FOREIGN KEY (baseline_id, design_item_id) REFERENCES baseline_item(baseline_id, design_item_id),
  FOREIGN KEY (baseline_id, project_id)     REFERENCES baseline(id, project_id)
);
CREATE TRIGGER trg_baseline_item_source_immutable BEFORE UPDATE ON baseline_item_source
  FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_baseline_item_source_document ON baseline_item_source(source_document_version_id);

-- 읽는 쪽이 항목 버전의 출처 칼럼 대신 이 뷰를 조인한다. source_status 가 NULL 이면 덮어쓴 행이 없다는 뜻이다.
CREATE VIEW baseline_item_effective_source AS
SELECT bi.baseline_id,
       bi.project_id,
       bi.design_item_id,
       bi.design_item_version_id,
       COALESCE(s.source_document_version_id, v.source_document_version_id) AS source_document_version_id,
       COALESCE(s.source_locator, v.source_locator)                         AS source_locator,
       s.source_status                                                      AS source_status
  FROM baseline_item bi
  JOIN design_item_version v ON v.id = bi.design_item_version_id AND v.design_item_id = bi.design_item_id
  LEFT JOIN baseline_item_source s ON s.baseline_id = bi.baseline_id AND s.design_item_id = bi.design_item_id;
