-- postgres-exporter 전용 계정 (S15P31A108-263). 절차는 docs/infra/monitoring.md "DB 수집 계정".
--
-- 비밀번호는 인자로 받지 않고 psql 이 환경변수 MONITOR_DB_PASSWORD 에서 읽는다(\getenv, psql 15+).
-- 인자로 넘기면 컨테이너 프로세스 목록(ps)에 값이 보인다.
-- 다시 돌려도 된다: 계정이 있으면 비밀번호만 맞춘다(비밀번호 교체도 이 파일로 한다).
--
-- 권한은 pg_monitor 하나다. 통계 뷰(pg_stat_*)·설정은 읽지만 테이블 데이터(문서 원문 등)는 읽지 못한다.

\set ON_ERROR_STOP on
\getenv monitor_password MONITOR_DB_PASSWORD

\if :{?monitor_password}
\else
  \set monitor_password ''
\endif

-- 없거나 빈 값이면 빈 비밀번호 계정이 생기므로 오류로 멈춘다(종료 코드 3). 서버 값은 48자 난수다
SELECT length(:'monitor_password') < 16 AS password_too_short \gset
\if :password_too_short
  DO $$ BEGIN RAISE EXCEPTION 'MONITOR_DB_PASSWORD 가 없거나 16자 미만이라 멈춤'; END $$;
\endif

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beez_monitor') AS need_create \gset

\if :need_create
  CREATE ROLE beez_monitor LOGIN PASSWORD :'monitor_password' CONNECTION LIMIT 3;
\else
  ALTER ROLE beez_monitor LOGIN PASSWORD :'monitor_password' CONNECTION LIMIT 3;
\endif

GRANT pg_monitor TO beez_monitor;
-- 수집 쿼리는 읽기만 한다. 실수로 쓰기 문이 나가도 막히게 한다
ALTER ROLE beez_monitor SET default_transaction_read_only = on;

-- 확인: pg_monitor 만 있고, 테이블 권한은 0 이어야 한다
SELECT r.rolname,
       ARRAY(SELECT b.rolname FROM pg_auth_members m JOIN pg_roles b ON b.oid = m.roleid
             WHERE m.member = r.oid) AS member_of,
       (SELECT count(*) FROM information_schema.table_privileges WHERE grantee = 'beez_monitor') AS table_grants
FROM pg_roles r
WHERE r.rolname = 'beez_monitor';
