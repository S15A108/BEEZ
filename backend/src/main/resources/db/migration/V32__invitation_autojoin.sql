-- S15P31A108-220 이메일 초대·초대 자동 참여 (Q05, 2026-10-29).
-- V28~V31 은 열린 PR(#125·#123·#127·-182)이 쓴다. outOfOrder 가 꺼져 있어 이 파일은 그 PR 들이 머지된 뒤 머지한다.

-- 초대 만료를 없앤다 (Q05 6). 잘못 보낸 초대는 폐기(MEM-A03)로 거둔다.
-- 예전 행의 만료 시각도 함께 지운다 — 남겨 두면 목록 응답에 의미 없는 expires_at 이 보인다.
ALTER TABLE invitation DROP COLUMN expires_at;

-- 로그인·가입 때 그 이메일의 대기 초대를 찾는다 (ACC-A01·A02 자동 참여). 매 로그인마다 도는 조회다.
-- 이메일은 초대·계정 모두 소문자로 저장하므로 lower() 없이 같은지만 본다.
CREATE INDEX ix_invitation_pending_email ON invitation(email)
  WHERE accepted_at IS NULL AND revoked_at IS NULL AND email IS NOT NULL;
