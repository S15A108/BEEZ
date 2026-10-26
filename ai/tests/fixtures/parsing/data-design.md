# 데이터 설계

## users

| 컬럼 | 타입 | NULL | 키 | 설명 |
| --- | --- | --- | --- | --- |
| id | bigint | N | PK | 사용자 식별자 |
| legacy_id | varchar(30) | Y | - | 외부 식별자이며 FK가 아님 |

## documents

| 컬럼 | 타입 | NULL | 키 | 설명 |
| --- | --- | --- | --- | --- |
| id | bigint | N | PK | 문서 식별자 |
| owner_id | bigint | N | FK | users.id 참조 |

documents.owner_id → users.id. 삭제는 RESTRICT 정책이다.
