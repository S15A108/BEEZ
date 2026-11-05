-- 비비 답변 발급 기록과 답변 피드백 (BEE-A01 answer_id, BEE-A03, BEE-A04 보조 인덱스).
-- 대화 본문은 저장하지 않는다(Q13). 피드백은 본인이 받은 답변의 ID와 근거 문서 버전만 남긴다.
CREATE TABLE bee_answer (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  mode TEXT NOT NULL,
  document_version_ids JSONB NOT NULL DEFAULT '[]',
  baseline_id BIGINT REFERENCES baseline(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(id, project_id)
);
CREATE TABLE bee_answer_feedback (
  answer_id BIGINT NOT NULL REFERENCES bee_answer(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  rating TEXT NOT NULL CHECK (rating IN ('HELPFUL','WRONG_SOURCE')),
  comment TEXT CHECK (comment IS NULL OR length(comment) <= 1000),
  document_version_ids JSONB NOT NULL DEFAULT '[]',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(answer_id, account_id)
);
-- BEE-A04: 진행 중인 문서 검토만 빠르게 찾는다
CREATE INDEX ix_document_bee_review_request_running ON document_bee_review_request(created_at DESC) WHERE status = 'RUNNING';
