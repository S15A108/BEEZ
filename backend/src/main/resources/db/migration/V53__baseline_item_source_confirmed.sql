-- S15P31A108-422 시작된 프로젝트에서 팀장이 설계 항목의 출처 위치를 확정한다.
--
-- 출처 문서는 있는데 위치가 없거나(근거 없이 채택한 후보) 문서가 바뀌어 위치를 옮기지 못한(NEEDS_CONFIRMATION) 항목은
-- 근거 수집이 그 항목을 쓸 수 없는 근거로 보아 검증이 영구히 막혔다. 항목 버전은 승인 뒤 불변이고 새 버전을 만들면 작업 계획의
-- 참조가 모두 낡아지므로, -406 과 같이 "그 기준에서의 유효 출처"만 기록한다.
--   - CONFIRMED: 사람이 현재 기준 문서 버전에서 위치를 지정했다. source_locator 는 줄 범위({type:text_lines,...}) 이거나
--     인용 없음 표시({no_quote:true,...})다. remapped_from_document_version_id 는 source_document_version_id 와 같다
--     (줄 번호가 가리키는 문서 버전).
--   - confirmed_by·confirmed_at: 확정한 사람과 때. CONFIRMED 행에만 있다.
ALTER TABLE baseline_item_source DROP CONSTRAINT baseline_item_source_source_status_check;
ALTER TABLE baseline_item_source ADD CONSTRAINT baseline_item_source_source_status_check
  CHECK (source_status IN ('REMAPPED', 'NEEDS_CONFIRMATION', 'CONFIRMED'));
ALTER TABLE baseline_item_source ADD COLUMN confirmed_by BIGINT REFERENCES account(id);
ALTER TABLE baseline_item_source ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE baseline_item_source ADD CONSTRAINT baseline_item_source_confirmed_by_check
  CHECK ((source_status = 'CONFIRMED') = (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL));

-- 행은 기본적으로 고치지 않는다. 같은 기준·항목·문서 버전에서 사람이 위치를 (다시) 확정하는 것만 허용한다.
-- 자동으로 옮긴 위치(REMAPPED)가 틀렸을 때도 사람이 바로잡을 수 있어야 하므로 이전 상태는 가리지 않는다.
CREATE FUNCTION baseline_item_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.source_status = 'CONFIRMED'
     AND NEW.baseline_id = OLD.baseline_id
     AND NEW.project_id = OLD.project_id
     AND NEW.design_item_id = OLD.design_item_id
     AND NEW.source_document_version_id = OLD.source_document_version_id
     AND NEW.created_at = OLD.created_at THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION '% is append-only except source confirmation', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END $$;
DROP TRIGGER trg_baseline_item_source_immutable ON baseline_item_source;
CREATE TRIGGER trg_baseline_item_source_immutable BEFORE UPDATE ON baseline_item_source
  FOR EACH ROW EXECUTE FUNCTION baseline_item_source_guard();
