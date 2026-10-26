-- 작업·계획 테스트용 기본 데이터. 다른 팀원 API(계정·프로젝트·설계)가 없어도 돌아가도록 SQL 로 직접 넣는다.
-- 번호는 IDENTITY 가 매기므로 고정하지 않고, 테스트는 이름(email, key)으로 조회해 번호를 얻는다.
--
-- 계정: leader@test (팀장), member@test (팀원), outsider@test (프로젝트 밖)
-- 프로젝트 1개, 설계 항목 API-01(v1), API-02(v1), baseline v1 에 둘 다 포함. 에이전트 토큰은 member 소유.

insert into account (email, password_hash, display_name) values
  ('leader@test', 'x', '팀장'),
  ('member@test', 'x', '팀원'),
  ('outsider@test', 'x', '외부인');

insert into project (name, owner_account_id)
select 'P', id from account where email = 'leader@test';

insert into project_member (project_id, account_id, role)
select p.id, a.id, 'LEADER' from project p, account a where p.name = 'P' and a.email = 'leader@test';
insert into project_member (project_id, account_id, role)
select p.id, a.id, 'MEMBER' from project p, account a where p.name = 'P' and a.email = 'member@test';

insert into agent_token (project_id, account_id, name, token_hash)
select p.id, a.id, 'member-laptop', 'hash-member' from project p, account a where p.name = 'P' and a.email = 'member@test';

insert into design_item (project_id, key, kind)
select id, 'API-01', 'API_CONTRACT' from project where name = 'P';
insert into design_item (project_id, key, kind)
select id, 'API-02', 'API_CONTRACT' from project where name = 'P';

insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
select id, 1, 'APPROVED', 'GET /users', 1, '{"method":"GET","path":"/users"}', 'h-api01-v1', 'HUMAN', now()
  from design_item where key = 'API-01';
insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
select id, 1, 'APPROVED', 'GET /orders', 1, '{"method":"GET","path":"/orders"}', 'h-api02-v1', 'HUMAN', now()
  from design_item where key = 'API-02';

insert into baseline (project_id, version_no, published_by)
select p.id, 1, a.id from project p, account a where p.name = 'P' and a.email = 'leader@test';

insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id)
select b.id, b.project_id, di.id, div.id
  from baseline b
  join design_item di on di.project_id = b.project_id
  join design_item_version div on div.design_item_id = di.id and div.version_no = 1
 where b.version_no = 1;

update project set current_baseline_id = (select id from baseline where version_no = 1 and project_id = project.id)
 where name = 'P';
