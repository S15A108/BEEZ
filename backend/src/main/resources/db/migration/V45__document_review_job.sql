-- 문서 비비 검토(DOC-A16)를 접수 → 워커 작업으로 바꾼다(S15P31A108-391). 새 테이블 없이 기존 요청 행을 작업으로 쓴다.
-- 상태는 그대로 RUNNING·COMPLETED·FAILED 이고 RUNNING 이 "대기"와 "실행 중"을 함께 뜻한다(lease_until 이 없으면 아직 아무 워커도 잡지 않음).
--   project_id·expected_revision: 워커가 호출자 문맥 없이 다시 확인하는 데 쓴다.
--   input: AI 에 보낼 입력 스냅샷. 끝나면 비운다(완료 입력은 document_bee_review.input 에 남는다).
--          이 열이 없던 시절(동기 호출)에 남은 RUNNING 행은 input 이 NULL 이라 워커가 잡지 않고 기존 10분 규칙으로 실패 처리된다.
--   lease_token·lease_until·attempts: 다른 워커가 이어받았는지 가리는 울타리와 재시도 횟수(DocumentIndexWorker 와 같은 방식).
ALTER TABLE document_bee_review_request
  ADD COLUMN project_id BIGINT REFERENCES project(id),
  ADD COLUMN expected_revision BIGINT,
  ADD COLUMN input JSONB,
  ADD COLUMN lease_token UUID,
  ADD COLUMN lease_until TIMESTAMPTZ,
  ADD COLUMN attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
