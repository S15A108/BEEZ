-- S15P31A108-159 비동기 검증 접수·실행. 의미 분석 결과(FindingCandidate)의 검사 키와 근거 키를 발견 항목에 남긴다
-- (AI-A02 "후보의 check_key·evidence_ids 영속화 구조는 -159 에서 정한다"). 규칙 finding 은 두 칸이 비어 있다.
-- 이 표를 쓰는 코드는 아직 없어 기존 행이 없다. 번호는 V16 — dev 의 V13(변경 제안 발행)·V14(문서 청크 출처)·V15(회의 충돌 모델) 뒤. 머지 직전 dev 최대 번호 + 1 로 다시 맞춘다.

ALTER TABLE validation_finding
  ADD COLUMN check_key    TEXT,
  ADD COLUMN evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence_ids) = 'array');

-- 실행(시도)마다 모델 호출 사용량을 남긴다. 판정을 버린 실행(DISCARDED)·만료·오류도 모델을 불렀으면 남겨 비용 집계(-168)에 쓴다.
-- 모델을 안 부른 실행은 NULL. 모양은 AI-A02 응답의 usage 배열 그대로.
ALTER TABLE validation_run
  ADD COLUMN usage JSONB CHECK (usage IS NULL OR jsonb_typeof(usage) = 'array');

-- 큐 조회 인덱스(ix_job_ready)와 작업별 이력 인덱스(ix_validation_task)는 V2 에 이미 있다.
