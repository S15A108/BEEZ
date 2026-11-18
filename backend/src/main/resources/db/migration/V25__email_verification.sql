-- 가입 이메일 인증 (S15P31A108-219, ACC-A11·A12, ACC-A01 verified_token).
-- 인증 요청 한 번이 한 행이다. 코드 단계(code_*)와 확인 뒤 가입 토큰 단계(token_*)를 같은 행에 둔다.
-- V24(웹 세션) 뒤 번호다. 알림(-181, PR #99)은 V26 을 쓴다 — 이 PR 이 먼저 머지돼야 한다.
CREATE TABLE email_verification (
    id                BIGSERIAL PRIMARY KEY,
    -- API 의 verification_id. 순번(id)을 내주면 남의 요청 ID 를 추측해 틀린 코드로 잠글 수 있어 난수로 따로 둔다
    public_id         TEXT        NOT NULL UNIQUE,
    email             TEXT        NOT NULL,                -- Account.normalizeEmail 로 소문자·공백 제거
    code_hash         TEXT        NOT NULL,                -- 6자리 코드의 bcrypt 해시. 원문은 저장하지 않는다
    code_expires_at   TIMESTAMPTZ NOT NULL,
    failed_attempts   INT         NOT NULL DEFAULT 0,
    -- 코드를 더 쓸 수 없게 된 시각: 다시 받기·틀린 횟수 초과·확인 성공. 만료는 code_expires_at 으로 따로 본다
    closed_at         TIMESTAMPTZ,
    token_hash        TEXT,                                -- verified_token 의 SHA-256. 확인 성공 때 채운다
    token_expires_at  TIMESTAMPTZ,
    token_consumed_at TIMESTAMPTZ,                         -- 가입 성공 때 채운다
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_email_verification_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_email_verification_token_pair CHECK ((token_hash IS NULL) = (token_expires_at IS NULL)),
    CONSTRAINT ck_email_verification_consumed CHECK (token_consumed_at IS NULL OR token_hash IS NOT NULL)
);

-- 이메일당 열린 코드는 하나다. 동시에 두 번 보내면 늦은 쪽이 이 제약에 걸려 429 가 된다
CREATE UNIQUE INDEX ux_email_verification_open ON email_verification(email) WHERE closed_at IS NULL;
-- 가입(ACC-A01) 때 토큰 해시로 찾는다
CREATE UNIQUE INDEX ux_email_verification_token ON email_verification(token_hash) WHERE token_hash IS NOT NULL;
-- 다시 받기 간격·시간당 발송 한도를 셀 때 이메일별 최근 행을 본다
CREATE INDEX ix_email_verification_email_created ON email_verification(email, created_at);
-- 서버 전체 시간당 발송 상한을 셀 때 최근 행만 본다
CREATE INDEX ix_email_verification_created ON email_verification(created_at);
