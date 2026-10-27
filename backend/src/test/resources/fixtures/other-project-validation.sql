-- 다른 프로젝트 Q 와, 그 안의 PASS 검증 하나. 남의 검증 번호로 START 할 때 정보가 새지 않는지 볼 때 쓴다.
-- project-basic.sql 다음에 실행한다 (leader@test 계정을 쓴다).
-- Q 의 검증은 설계 항목 Q-API-77 을 참조한다. 이 항목 번호나 "설계 참조가 오래됨" 이 응답에 나오면 샌 것이다.

insert into project (name, owner_account_id)
select 'Q', id from account where email = 'leader@test';

insert into design_item (project_id, key, kind)
select id, 'Q-API-77', 'API_CONTRACT' from project where name = 'Q';

insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
select id, 1, 'APPROVED', 'secret', 1, '{}', 'h-q-v1', 'HUMAN', now() from design_item where key = 'Q-API-77';

insert into baseline (project_id, version_no, published_by)
select p.id, 1, a.id from project p, account a where p.name = 'Q' and a.email = 'leader@test';

insert into task (project_id, key, title, status)
select id, 'T-1', 'q', 'DRAFT' from project where name = 'Q';

insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id)
select t.id, 1, 'q', 'q', 'h-q', a.id
  from task t join project p on p.id = t.project_id, account a
 where p.name = 'Q' and a.email = 'leader@test';

insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
select t.id, r.id, b.id, 'PASS', 'AGENT', 's-q', 'r1'
  from task t
  join project p on p.id = t.project_id
  join task_plan_revision r on r.task_id = t.id
  join baseline b on b.project_id = p.id
 where p.name = 'Q';

-- 일부러 Q 의 현재 baseline 은 비워 둔다. G2 가 이 검증을 읽으면 반드시 "오래됨" 으로 걸리므로 새는지 바로 드러난다.
insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
select v.id, di.id, div.id, 'READS'
  from validation v
  join task t on t.id = v.task_id
  join project p on p.id = t.project_id
  join design_item di on di.project_id = p.id and di.key = 'Q-API-77'
  join design_item_version div on div.design_item_id = di.id
 where p.name = 'Q';
