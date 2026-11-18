-- 웹 세션 저장소 (S15P31A108-242). Spring Session JDBC 4.1 의 schema-postgresql.sql 과 같은 구조다.
-- 테이블·컬럼 이름은 라이브러리 SQL 이 그대로 쓰므로 바꾸지 않는다 (spring.session.jdbc.initialize-schema=never).
create table spring_session (
    primary_id char(36) not null,
    session_id char(36) not null,
    creation_time bigint not null,
    last_access_time bigint not null,
    max_inactive_interval int not null,
    expiry_time bigint not null,
    principal_name varchar(100),       -- 로그인한 계정 ID (SessionUser.getName). -171 에서 그 사용자의 다른 세션을 찾는다
    constraint spring_session_pk primary key (primary_id)
);

create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);   -- 만료 세션 정리
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36) not null,
    attribute_name varchar(200) not null,
    attribute_bytes bytea not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references spring_session (primary_id) on delete cascade
);
