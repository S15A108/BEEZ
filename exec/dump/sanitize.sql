-- 제출용 DB 덤프 비식별화 (S15P31A108-461). exec/dump/make-public-dump.sh 가 임시 DB 에서 실행한다.
-- 원본 서버 DB 에는 절대 실행하지 않는다. 계정 이메일·이름·비밀번호와 로그인·인증·초대 흔적을 지운다.
--
-- 입력: psql 변수 demo_password (모든 계정에 넣을 시연용 비밀번호)
--       psql 변수 keep_names     (이름을 그대로 둘 시연 계정 표시 이름, 쉼표로 구분. 예: 전진,백엔드진)

\set ON_ERROR_STOP on
BEGIN;
-- 제출한 검토 자료처럼 수정을 막는 트리거가 있다. 임시 DB 에서 이 트랜잭션 동안만 트리거를 끈다(슈퍼유저 필요)
SET LOCAL session_replication_role = replica;

-- 1) 계정: 이메일은 user<id>@example.com, 비밀번호는 시연용 하나로 통일, 시연 계정이 아닌 이름은 사용자<id>
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE account
   SET email = 'user' || id || '@example.com',
       password_hash = '{bcrypt}' || crypt(:'demo_password', gen_salt('bf', 10)),
       must_change_password = false,
       display_name = CASE
           WHEN display_name = ANY (string_to_array(:'keep_names', ',')) THEN display_name
           ELSE '사용자' || id
       END;
DROP EXTENSION pgcrypto;

-- 2) 로그인 세션, 가입 인증, 임시 비밀번호: 시연에 필요 없고 다른 테이블이 참조하지 않으므로 비운다
TRUNCATE spring_session_attributes, spring_session, email_verification, temporary_password;

-- 3) 초대: 계정과 연결돼 있어 지우지 않고, 받는 사람 이메일과 토큰만 바꾼다
UPDATE invitation
   SET email = 'invitee' || id || '@example.com',
       token_hash = md5('revoked-invitation-' || id);

-- 4) 에이전트 토큰: 작업·감사 기록이 참조하므로 행은 두고, 원래 토큰으로는 접속할 수 없게 해시만 바꾼다
UPDATE agent_token SET token_hash = md5('revoked-agent-token-' || id);
UPDATE agent_session SET session_key = md5('revoked-agent-session-' || id);

-- 5) 문서·작업 계획·AI 기록 본문에 적힌 이메일: 모든 텍스트·JSON 컬럼에서 example.com, example.test, beez.ai.kr 밖의
--    주소를 redacted@example.com 으로 바꾼다. 바뀐 본문은 저장된 content_hash 와 달라질 수 있다(해당 문서만 해당)
DO $$
DECLARE
  col record;
  pattern constant text := '[A-Za-z0-9._%+-]+@(?!example\.com|example\.test|beez\.ai\.kr)[A-Za-z0-9.-]+\.[A-Za-z]{2,}';
BEGIN
  FOR col IN
    SELECT c.table_name, c.column_name, c.data_type
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
     WHERE c.table_schema = 'public'
       AND c.data_type IN ('text', 'character varying', 'jsonb')
       AND c.table_name <> 'flyway_schema_history'
  LOOP
    EXECUTE format(
      'UPDATE %I SET %I = regexp_replace(%I::text, %L, %L, %L)::%s WHERE %I::text ~ %L',
      col.table_name, col.column_name, col.column_name, pattern, 'redacted@example.com', 'g',
      CASE col.data_type WHEN 'jsonb' THEN 'jsonb' ELSE 'text' END,
      col.column_name, pattern);
  END LOOP;
END $$;

COMMIT;
