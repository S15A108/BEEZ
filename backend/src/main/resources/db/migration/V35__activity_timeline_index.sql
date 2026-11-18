-- 프로젝트 활동 타임라인 조회 (S15P31A108-183, MON-A04).
-- dev V33 다음, 열린 #149 의 V34 다음 번호. outOfOrder 꺼짐이라 #149 보다 먼저 머지되면 #149 를 올리거나 이 번호를 다시 본다.

-- 타임라인 정렬·cursor 는 id 내림차순이다(계약 10-notification-activity-contract.md 4절). V2 의 occurred_at 인덱스는
-- 이 정렬에 쓰이지 않고, 다른 조회도 없어 같은 이름으로 바꾼다.
DROP INDEX ix_activity_timeline;
CREATE INDEX ix_activity_timeline ON activity_event(project_id, id DESC);

-- 한 변경 제안의 지나온 길(change_proposal_id 필터). 보조 참조가 있는 행만 담는다.
CREATE INDEX ix_activity_change_proposal ON activity_event(project_id, change_proposal_id, id DESC)
  WHERE change_proposal_id IS NOT NULL;
