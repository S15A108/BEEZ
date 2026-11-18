-- 임시 비밀번호 발급 (S15P31A108-171, ACC-A07 B안). 비밀번호 찾기는 재설정 링크(ACC-A08·A09, 폐지 예정) 대신 이것을 쓴다.
-- 알림(-181, PR #99)의 V26 뒤 번호다 — #99 가 먼저 머지돼야 한다 (flyway outOfOrder 꺼짐).

-- 임시 비밀번호로 로그인하면 true. 비밀번호 변경(ACC-A06)이 성공하면 false (ACC-A02 응답 must_change_password)
ALTER TABLE account ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- 비밀번호 찾기 요청 한 번이 한 행이다. 계정이 없거나 이름이 틀린 요청도 행을 남긴다 —
-- 발송 한도(429)를 이메일마다 똑같이 세야 응답으로 가입 여부가 드러나지 않는다. 그런 행은 account_id·password_hash 가 NULL 이고 바로 닫힌다.
CREATE TABLE temporary_password (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT        NOT NULL,             -- Account.normalizeEmail 로 소문자·공백 제거
    account_id    BIGINT      REFERENCES account(id),
    password_hash TEXT,                             -- 임시 비밀번호의 bcrypt 해시. 원문은 메일에만 있다
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,                      -- 이것으로 로그인한 시각
    -- 더 쓸 수 없게 된 시각: 사용·새로 발급·비밀번호 변경·발송 실패. 만료는 expires_at 으로 따로 본다
    closed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_temporary_password_pair CHECK ((account_id IS NULL) = (password_hash IS NULL)),
    CONSTRAINT ck_temporary_password_used CHECK (used_at IS NULL OR closed_at IS NOT NULL)
);

-- 계정당 쓸 수 있는 임시 비밀번호는 하나다
CREATE UNIQUE INDEX ux_temporary_password_open ON temporary_password(account_id)
    WHERE closed_at IS NULL AND account_id IS NOT NULL;
-- 다시 받기 간격·이메일당 시간당 한도
CREATE INDEX ix_temporary_password_email_created ON temporary_password(email, created_at);
-- 서버 전체 시간당 발송 상한
CREATE INDEX ix_temporary_password_created ON temporary_password(created_at);
