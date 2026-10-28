-- S15P31A108-174 팀원 초대·관리. V2 를 명세(00-common Invitation·Member, MEM-A01·A03·A07)에 맞춘다.
-- V3 은 문서 처리(-192, PR #28)가 쓴다. 이 파일은 그 뒤 번호다.

-- 초대: 이메일 지정은 선택이다 (MEM-A01). 지정한 경우 소문자로 저장한다.
ALTER TABLE invitation ALTER COLUMN email DROP NOT NULL;
-- 초대 폐기 (MEM-A03). 폐기 후 링크 수락을 거부한다.
ALTER TABLE invitation ADD COLUMN revoked_at TIMESTAMPTZ;
-- 수락과 수락 계정은 함께 찍히고, 수락된 초대는 폐기 상태가 될 수 없다
ALTER TABLE invitation ADD CONSTRAINT ck_invitation_accepted_pair
  CHECK ((accepted_at IS NULL) = (accepted_account_id IS NULL));
ALTER TABLE invitation ADD CONSTRAINT ck_invitation_accepted_or_revoked
  CHECK (accepted_at IS NULL OR revoked_at IS NULL);
-- 초대 목록 (MEM-A02, id 내림차순 커서)
CREATE INDEX ix_invitation_project ON invitation(project_id, id);

-- 팀원 표시 정보 (MEM-A07). 담당 영역은 V2 의 area 를 쓴다 (API 필드 domain).
ALTER TABLE project_member ADD COLUMN role_label TEXT;
ALTER TABLE project_member ADD COLUMN color      TEXT;
