-- S15P31A108-212 팀원 제외·나가기. 멤버십이 끝난 이유를 남긴다 (Q05 초대 자동 참여, 2026-10-29).
-- 재초대 때 제외(REMOVED)된 사람은 로그인·가입으로 되살리고, 스스로 나간(LEFT) 사람은 메일 링크(MEM-A05)로만 되살린다 (-220).
-- V10 은 변경 제안 리비전(-195, PR #46)이 쓴다. 이 파일은 그 뒤 번호다.

ALTER TABLE project_member ADD COLUMN left_reason TEXT;
ALTER TABLE project_member ADD CONSTRAINT ck_member_left_reason
  CHECK (left_reason IN ('REMOVED', 'LEFT'));
-- 활성 멤버십에는 끝난 이유가 없다. 이 마이그레이션 전에 끝난 행은 이유를 모르므로 비활성인데 NULL 일 수 있다.
ALTER TABLE project_member ADD CONSTRAINT ck_member_left_reason_inactive
  CHECK (NOT active OR left_reason IS NULL);
