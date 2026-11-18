-- 작업 완료 검증 (S15P31A108-269, VAL-07 작은 범위). 완료 보고에 "실제로 바꾼 설계 항목" 칸을 더한다.
-- 번호: dev 가 V29 까지라 V30. 열린 브랜치가 V31(활동 이벤트)·V32(초대 자동 참여)를 쓰고 있다.
-- NULL = 에이전트가 이 칸을 보내지 않음 (범위 이탈을 판단할 수 없음). [] = 바꾼 항목이 없다고 보고함.
-- 값은 design_item.id 숫자 배열이다. 보고는 불변(UPDATE 차단 트리거)이라 저장할 때 함께 넣는다.
-- 범위 이탈 대조 결과는 저장하지 않는다 — 보고·계획 리비전·계획 참조가 모두 불변이라 읽을 때 같은 결과로 다시 계산된다.
ALTER TABLE task_completion_report ADD COLUMN changed_design_item_ids JSONB;
ALTER TABLE task_completion_report ADD CONSTRAINT task_completion_report_changed_items_array
    CHECK (changed_design_item_ids IS NULL OR jsonb_typeof(changed_design_item_ids) = 'array');
