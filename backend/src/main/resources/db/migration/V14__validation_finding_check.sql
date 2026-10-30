-- S15P31A108-159 비동기 검증 접수·실행. 의미 분석 결과(FindingCandidate)의 검사 키와 근거 키를 발견 항목에 남긴다
-- (AI-A02 "후보의 check_key·evidence_ids 영속화 구조는 -159 에서 정한다"). 규칙 finding 은 두 칸이 비어 있다.
-- 이 표를 쓰는 코드는 아직 없어 기존 행이 없다. 번호는 V14 — 먼저 머지된 변경 제안 발행(#62)의 V13 바로 뒤.

ALTER TABLE validation_finding
  ADD COLUMN check_key    TEXT,
  ADD COLUMN evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence_ids) = 'array');

-- 큐 조회 인덱스(ix_job_ready)와 작업별 이력 인덱스(ix_validation_task)는 V2 에 이미 있다.
