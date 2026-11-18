-- 테스트 사이 초기화. account 를 비우면 CASCADE 로 그 아래 모든 테이블(project → task … )이 같이 비워진다.
-- restart identity: 번호를 1 부터 다시 매긴다.
truncate table account restart identity cascade;
