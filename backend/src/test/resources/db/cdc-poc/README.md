# 테스트 전용 CDC PoC 스키마

`R__cdc_poc_fixture.sql`은 테스트 프로필에서만 실행되는 Flyway repeatable fixture다. 운영 Flyway 위치에는 포함되지 않으며 일반 버전 마이그레이션이 모두 실행된 뒤 outbox, inbox, cursor, job 순서로 생성한다. 버전 번호를 사용하지 않으므로 운영의 V13 이후 마이그레이션과 충돌하지 않는다.

Flyway는 내용이 바뀌면 repeatable fixture를 다시 실행한다. `IF NOT EXISTS`는 재실행 시 기존 행을 보존하지만 기존 테이블의 컬럼이나 제약 조건을 새 정의로 변경하지 않는다. 기존 fixture DB에 이전 스키마가 남아 있다면 별도의 격리된 새 테스트 DB에서 검증해야 한다. 실제 DB나 기존 볼륨을 자동 삭제하지 않는다.
