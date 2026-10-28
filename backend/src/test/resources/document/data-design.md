# 데이터 설계

## document

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| id | BIGINT | PK |
| project_id | BIGINT | NOT NULL, project.id FK |
| type | TEXT | PLAN, ERD, REQ_SPEC, API_SPEC |

이 파일은 Markdown 설명 자료이며 PostgreSQL DDL 입력이 아니다.
