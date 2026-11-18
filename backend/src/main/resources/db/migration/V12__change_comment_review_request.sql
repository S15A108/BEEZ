-- 변경 제안 의견·검토 요청 (S15P31A108-196)
-- 검토 요청 대상을 여러 명 받으려고 대상 칸(target_account_id)을 별도 표로 옮긴다.
-- 기존 target_account_id 와 REVIEW_REQUEST 짝 검사는 단일 대상 전제라 걷어낸다. 컬럼 자체는 남겨 둔다(V2 데이터 호환).
ALTER TABLE change_comment DROP CONSTRAINT IF EXISTS change_comment_check;

-- 재시도 식별(Idempotency-Key)은 195 와 같은 공용 design_http_command 에 저장하므로 이 표에는 칼럼을 두지 않는다.

-- 의견이 어느 제출 리비전을 보고 쓴 것인지 (V10 change_proposal_revision). 재제출 뒤에도 이전 리비전의 의견이 구분되어 남는다.
-- 기존 의견은 V10 이 기존 제안을 리비전 1 로 옮겼으므로 1 로 채운다.
ALTER TABLE change_comment ADD COLUMN proposal_revision_no INT NOT NULL DEFAULT 1 CHECK (proposal_revision_no > 0);
ALTER TABLE change_comment ALTER COLUMN proposal_revision_no DROP DEFAULT;
ALTER TABLE change_comment ADD CONSTRAINT fk_change_comment_revision
  FOREIGN KEY (change_proposal_id, proposal_revision_no) REFERENCES change_proposal_revision(change_proposal_id, revision_no);

-- 의견 목록을 제안별 작성 순서로 읽는다
CREATE INDEX ix_change_comment_proposal ON change_comment(change_proposal_id, id);

-- 의견에 지정한 멤버(검토 요청 대상·관련 멤버). 계정으로 저장하고 응답에서 멤버 번호로 바꾼다
CREATE TABLE change_comment_target (
  change_comment_id BIGINT NOT NULL REFERENCES change_comment(id) ON DELETE CASCADE,
  account_id        BIGINT NOT NULL REFERENCES account(id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (change_comment_id, account_id)
);
