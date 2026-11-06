-- 끝난 멤버십을 담당자로 가리키는 열린 작업의 담당자를 비운다 (S15P31A108-281, 1회 정리).
-- 번호: dev 가 V37 까지이고 열린 PR #178(V38)·#181(V39)이 있어 V40.
--
-- PR #118(-171, 11-05 dev 배포)부터 -281 전까지 탈퇴한 팀원의 열린 작업은 담당자가 그대로 남았다.
-- 지금은 탈퇴·나가기·제외가 모두 담당자를 비우므로(-301·-281), 그 사이에 쌓인 행만 같은 결과로 맞춘다.
-- 앱 경로와 같게 담당자만 비우고 상태·검증은 그대로 두며, lock_version 을 올려 화면이 쥔 옛 번호로는 바꿀 수 없게 한다.
-- 활동(TASK_ASSIGNEE_CHANGED)은 남기지 않는다 — 행위자(탈퇴한 본인)의 요청 안에서 생긴 일이 아니라 사후 정리라서다.
--
-- 보관된 프로젝트는 건드리지 않는다. 혼자 쓰던 프로젝트를 탈퇴로 함께 삭제하면 팀장 멤버십만 끝나고
-- 작업 담당자는 그대로 두는 것이 정상이다(-171).
-- 대상이 없으면 아무것도 바꾸지 않는다.

UPDATE task t
   SET assignee_member_id = NULL,
       lock_version       = t.lock_version + 1,
       updated_at         = now()
  FROM project_member m, project p
 WHERE m.id = t.assignee_member_id
   AND p.id = t.project_id
   AND m.active = false
   AND p.archived_at IS NULL
   AND t.status IN ('DRAFT', 'READY', 'IN_PROGRESS', 'WAITING');
