# 수동 설계 항목과 최초 기준 설계 계약

2026-10-28 사용자 확정 / S15P31A108-194. 공통 설계(111), 수동 항목 PLN-A06~09와 최초 발행 PLN-A10을 한 PR에서 반영한다. 후속 변경 제안·승인·재검증, 자동 질문·항목 추출·답변 기반 생성은 별도 범위다.

## 1. 항목 본문 v1 — Q01

`schema_version=1`, JSON 객체의 `description`은 모든 종류에서 필수인 비어 있지 않은 문자열이다. 화면 표시 문장과 DB `design_item_version.title`에 같은 값을 사용한다. 원문 출처가 없는 사람이 합의한 항목도 등록할 수 있으며 origin=HUMAN이다.

| kind | 필수 content 필드 | 예 |
| --- | --- | --- |
| REQUIREMENT | description | 로그인한 사용자는 참여 프로젝트를 조회한다. |
| POLICY | description | 프로젝트 삭제는 인간 팀장만 수행한다. |
| CONSTRAINT | description | 문서 업로드는 파일당 1 MiB 이하이다. |
| API_CONTRACT | description, method, path | method=GET, path=/api/projects |
| DATA_CONTRACT | description, entity, fields | entity=account, fields=[{"name":"id","type":"bigint"}] |

- description은 10,000자 이하. API method는 대문자 GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS/TRACE, path는 `/`로 시작하고 공백·제어 문자·쿼리·fragment가 없는 2,000자 이하 경로다.
- DATA_CONTRACT의 entity는 비어 있지 않은 문자열, fields는 배열이다. 배열 원소의 세부 스키마는 이 버전에서 강제하지 않는다. 추가 JSON 필드는 보존한다.
- 다른 schema_version·미지원 kind·잘못된 필수 필드는 400 validation_failed. JSON 최대 크기는 canonical UTF-8 1 MiB, 중첩 깊이는 32다.
- 표시 key는 프로젝트 잠금 아래 종류별 REQ/API/ENT/POL/CON 접두사와 증가 번호를 서버가 발급한다(예: REQ-001). 제외한 번호도 재사용하지 않는다.
- source_document_version_id가 있으면 같은 프로젝트 원문 버전이어야 한다. source_location은 원문 버전과 함께만 보낼 수 있다. 출처 없는 항목에는 가짜 source를 반환하지 않는다.

## 2. 초안 수정·제외 — Q08

- 조회는 MEMBER, 추가·수정·제외·발행은 HUMAN LEADER. 모든 변경 요청은 기존 문서의 허용 Origin/Referer 검사를 재사용한다. 문서 전용 CSRF API는 추가하지 않는다.
- 최초 발행 전 수동 편집만 지원한다. 기존 baseline이 있으면 변경 제안 경로를 사용하며 이 command들은 409 baseline_exists로 거절한다.
- DRAFT 편집은 같은 항목 버전 행을 갱신한다. expected_version_id와 expected_content_hash를 모두 확인한다. 승인/제외된 버전·오래된 해시는 409 version_conflict다.
- `content_hash`는 JSON 객체 키를 재귀 정렬해 UTF-8 JSON을 직렬화한 SHA-256이다. 배열 순서·문자열 공백은 보존한다. JSON 객체 키 순서만 다른 재요청은 같은 입력이다.
- 제외는 DRAFT→REJECTED. 행과 본문은 보관하고 DRAFT 목록·발행 대상에서 제외한다. 복원은 후속이다. 기존 승인 항목 폐기는 변경 제안 RETIRE를 사용한다.
- 등록과 최초 발행은 Idempotency-Key 필수(제어 문자 없는 1~128자). 사용자/프로젝트/동작별로 24시간 같은 입력은 같은 응답, 다른 입력은 409 idempotency_conflict다. 수정·제외는 버전/본문 해시로 경합을 판정한다.
- 수동 항목/최초 발행의 멱등 응답은 V6 design_http_command에 기록한다. 기존 문서 전용 command 테이블을 혼용하지 않는다.

## 3. 전체 검토 스냅샷

PLN-A06의 DRAFT 응답은 필터·페이지와 무관한 프로젝트 전체 `draft_snapshot_hash`를 제공한다. 첫 페이지만 승인하는 것을 막기 위한 계약이다.

해시 입력은 프로젝트·현재 baseline, 문서별 최신 DRAFT의 문서 ID/종류/버전 ID/원본 해시, 모든 활성 DRAFT 항목의 항목 ID/key/kind/버전 ID/schema_version/본문 해시/원문 출처·locator다. 각각 안정적인 ID 순으로 정렬해 canonical JSON을 해시한다. 제외·추가·수정·대상 문서 변경·최초 발행이 스냅샷을 바꾼다. 색인 진행 상태는 검토 본문이 아니므로 해시에 넣지 않고 발행 시 별도 검사한다.

목록 읽기는 repeatable-read로 페이지와 해시를 같은 DB 스냅샷에서 구한다. cursor에는 마지막 버전 ID·스냅샷·프로젝트/필터 식별값을 넣는다. 다른 필터로 재사용하면 400 validation_failed, 페이지 사이 전체 초안이 바뀌면 409 draft_changed다. 기본 APPROVED 목록은 현재 baseline 항목만 반환한다.

## 4. 최초 승인·발행

사용자 확정: **필수 문서 4종의 색인이 모두 완료돼야 발행할 수 있다.** 수동 항목은 1개 이상이어야 한다. 자동 질문·후보 추출의 완료는 요구하지 않는다.

1. 현재 사람 팀장 검사 → project 행 잠금 → 현재 권한 재확인.
2. 기존 멱등 응답 재요청이면 같은 결과 반환. 새 요청이면 기존 baseline이 없어야 한다.
3. PLAN/REQUIREMENTS/ERD/API_SPEC 각 1개 최신 DRAFT, 전체 활성 DRAFT 항목의 정확한 ID 집합과 검토 스냅샷을 확인한다. 중복 ID·일부 항목만 제출·검토 후 변경은 허용하지 않는다. 항목 출처가 있으면 발행 문서 버전 중 하나여야 한다.
4. 각 문서의 processing.status=COMPLETED, source_status/index_status=READY, 현재 attempt/text_hash와 manifest 일치, 현재 embedding profile 일치, manifest 청크 수와 실제 임베딩 보유 청크 수 일치를 검사한다. 미완료·실패·누락·불일치는 409 analysis_not_completed다.
5. 같은 트랜잭션에서 문서/항목 APPROVED·승인자/시각, baseline v1·baseline_document·baseline_item, project.current_baseline_id와 멱등 응답을 기록한다. 중간 실패는 전체 rollback.

부족한 필수 문서는 400 required_documents_missing, 기존 baseline은 409 baseline_exists, 전체 집합/해시 불일치는 409 draft_changed다. baseline의 published_by/published_at을 응답의 approved_by/created_at으로 매핑한다. 승인 이력은 이 불변 baseline에 보존된다.

최초 발행에는 과거 baseline이 없으므로 기존 검증을 무효화하지 않는다. 변경 후 승인·발행·재검증은 CHG-A09 계약을 따른다. 타임라인 활동 이벤트 연결은 후속 104 범위로 유지하며 이 PR의 저장 성공을 이벤트 전달 완료로 보지 않는다.

## 5. 확인 시나리오

- 실제 로그인·현재 팀장·다른 프로젝트·에이전트·출처 검사.
- 추가/수정/제외, 5종 스키마, 초안/승인본 분리, 필터/페이지 밖의 수정 감지.
- 문서 4종 미준비 시 발행 차단, 전체 집합/검토 해시·출처 버전 검사.
- 동시 key 발급·동시 편집·멱등 발행·중간 DB 실패의 전체 rollback.

관련 API: [PLN-A06~10](../api/01-backend-rest.md#pln-a06), [공통 스키마](../api/00-common.md#schema-designitemversion).
