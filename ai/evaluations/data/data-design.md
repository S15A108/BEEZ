# 나래 데이터 설계

이 문서는 관계와 삭제 조건을 설명하는 실험용 설계다. 이름이 비슷하다는 이유로 외래 키 관계를 추론하지 않는다. 표에 FK라고 명시한 필드만 외래 키로 취급한다.

## accounts

| 컬럼 | 타입 | NULL | 키 | 설명 |
| --- | --- | --- | --- | --- |
| id | bigint | N | PK | 계정 식별자 |
| external_id | varchar(80) | Y | - | 외부 서비스 문자열, FK가 아님 |
| active | boolean | N | - | 활성 상태 |

계정은 여러 프로젝트에 참여할 수 있다. 탈퇴 기록과 사용자 표시는 별도 운영 정책에 따른다. 외부 서비스 문자열과 내부 숫자 식별자는 같은 값으로 비교하지 않는다.

## documents

| 컬럼 | 타입 | NULL | 키 | 설명 |
| --- | --- | --- | --- | --- |
| id | bigint | N | PK | 문서 식별자 |
| owner_id | bigint | N | FK | accounts.id 참조 |
| legacy_owner_id | varchar(80) | Y | - | 외부 표시값, FK가 아님 |

documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

문서를 생성한 계정이 존재해야 관계를 저장할 수 있다. 참조 중인 계정을 삭제할 때 documents를 자동으로 연쇄 삭제하지 않는다. 이 문서는 테이블별 물리 인덱스 이름과 계정 복구 기간을 정의하지 않는다.

## document_versions

| 컬럼 | 타입 | NULL | 키 | 설명 |
| --- | --- | --- | --- | --- |
| id | bigint | N | PK | 원문 버전 식별자 |
| document_id | bigint | N | FK | documents.id 참조 |
| content_hash | char(64) | N | - | 원본 bytes SHA-256 |

원문 버전은 생성 후 불변이다. 정규화 텍스트 해시는 원본 bytes 해시와 구분한다. 새 승인 기준본이 발행되더라도 이전 원문 버전의 해시와 내용을 바꾸지 않는다.

## 색인 청크

document_chunk의 유일성 기준은 document_version_id와 chunk_no의 조합이다.

여러 세대를 동시에 같은 번호로 저장하는 설계는 별도 변경이 필요하다. 완전한 후보를 준비한 후 트랜잭션으로 청크 전량과 READY manifest를 교체하는 방식을 검토한다. 여기에는 운영 DB migration 번호를 부여하지 않는다.
