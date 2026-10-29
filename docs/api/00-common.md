# BEEZ API Common

버전: 0.8 · 갱신일: 2026-10-28 · 개발 계약 초안

2026-10-27: 원문 RAG의 SourceLocator·RetrievalScope·RetrievedEvidence를 추가했다. 관련 내부 호출은 AI-A09와 AI-A04/05 확장을 따른다. 기존 문서/승인 상태나 비RAG 계약을 일괄 변경하지 않는다.

2026-10-28: 컨텍스트 조회 이력 CTX-A06과 ContextAccessLog 스키마를 추가했다(S15P31A108-165).

2026-10-28: 화면 수정안(v2)이 요구하는 계약을 반영했다(S15P31A108-218). 회원가입 이메일 인증, 임시 비밀번호, 공용 문서함의 비비 필수 검토·팀장 승인, 비비가 여는 회의와 회의 끝내기, 접속 현황이 새로 들어갔다. 시작 가이드 상태 저장은 브라우저에 두기로 하고 확장 후보로 내렸다. 새 API와 바뀐 필드는 상태를 '수정안 요청'으로 표시했다. 정책이 정해지지 않은 부분은 기본안으로 적고 9절 Q14~Q23에 올렸다.

## 1. 문서 안내

BEEZ의 웹 REST, MCP 도구, AI 내부 호출이 공유하는 규약·권한·데이터·책임 경계를 정의한다.

| 문서 | 범위 |
| --- | --- |
| [Backend REST](01-backend-rest.md) | 웹 → backend의 API별 요청·응답·오류·처리 규칙 |
| [MCP Tools](02-mcp-tools.md) | Claude Code → backend /mcp의 도구별 계약 |
| [AI Internal](03-ai-internal.md) | backend → ai의 분석·생성·임베딩 계약 |

기준 자료: [요구사항](../BEEZ_요구사항_명세서.md), [업무 분담안](../BEEZ_업무분담안.md), [아키텍처](../design/01-system-architecture.md), [시스템 플로우](../design/02-system-flow.md), [ERD](../design/03-erd.md), [기획서](../BEEZ_기획서.md).

상세 문서의 상태는 계약의 검토 수준이다. `설계 초안`은 업무 API의 구현 완료를 뜻하지 않는다. `담당자 검토`·`합의 필요`는 해당 절 또는 9절의 미결 정책을 확인해야 한다. 지정 경로는 아키텍처·플로우를 유지하고, 그 밖의 상세 경로·DTO는 이 계약의 개발 초안이다. API별 중첩 타입은 10절 공통 스키마로 연결된다.

### Base URL

| 구간 | 로컬 기준 | 경로 |
| --- | --- | --- |
| 웹 → backend | http://localhost:8080 | /api/** |
| 에이전트 → backend | http://localhost:8080 | /mcp |
| backend → ai | http://localhost:8000 또는 AI_BASE_URL | /v1/** |

운영 주소는 배포 설정을 따른다. 예제의 `beez.example`·토큰·ID·해시는 설명용 값이다. 파일 업로드는 multipart, 다운로드는 원본 MIME, 나머지는 JSON을 기본으로 한다.

## 2. 인증·권한

| 표기 | 충족 조건 | 추가 제한 |
| --- | --- | --- |
| PUBLIC | 세션 없이 접근 가능 | 가입·로그인·재설정·초대 미리보기·기동 확인 등 지정 경로 |
| USER | 유효한 사람 로그인 세션 | 프로젝트 멤버십을 뜻하지 않음. 본인 정보는 세션에서 식별 |
| MEMBER | USER이며 대상 프로젝트의 활성 멤버 | 프로젝트 팀장도 포함 |
| LEADER | MEMBER이며 현재 역할이 LEADER | 승인·발행·팀 관리는 사람 세션 필수 |
| TASK_OWNER | MEMBER이며 해당 작업의 현재 담당자 | 팀장도 담당자가 아니면 자동 대리 권한 없음. 개입 정책 Q02 |
| RECORD_AUTHOR | MEMBER이며 해당 기록의 작성자 | PRIVATE 읽기·기록 수정에 적용 |
| AGENT | 유효한 프로젝트별 에이전트 Bearer 토큰 | 매 호출 현재 멤버십·객체 소속·도구별 담당자 검사 |
| INTERNAL_SERVICE | 내부 서비스 Bearer 인증 통과 | AI 내부 경로만. 사람·에이전트 토큰으로 대체 불가 |
| BEE | 비비가 생성·분석한 결과의 처리 주체 | 로그인 역할이나 승인 자격이 아님. 내부 인증·기능 범위로 제한 |

`MEMBER + TASK_OWNER`는 두 조건을 모두 요구한다. 슬래시로 구분된 권한은 리소스 공개 범위에 따른 조건부 접근이며 각 상세 API의 처리 규칙을 따른다. 팀장 토큰으로 접속한 AGENT도 사람 승인·발행 API에는 접근할 수 없다.

웹 인증은 세션 쿠키, MCP는 프로젝트 토큰이다. 세션 쿠키 이름·만료·CSRF 헤더는 Q04에서 정한다. 쿠키 기반 변경 요청에는 확정된 CSRF 계약을 함께 적용한다. `X-Project-Id`는 권한의 근거가 아니며 프로젝트 경로·객체 소속·토큰 범위를 서버에서 대조한다. 다른 프로젝트 객체는 존재를 노출하지 않는 404로 거부한다.

다른 사람·다른 에이전트가 작성한 공유 기록·계획·패킷·회의 기록은 에이전트에게 **데이터**로 전달한다. 에이전트는 이를 동료의 요청이나 참고 정보로 다루며 자기 사용자의 지시로 취급하지 않는다. MCP 응답과 AI 입력에서 이 구분을 유지한다.

## 3. 데이터·응답 규약

| 항목 | 형식·처리 |
| --- | --- |
| JSON 키 | snake_case |
| ID | BIGINT 식별자를 십진 문자열로 전달. 예: "101". 계정·멤버·세션·버전 ID는 서로 다름 |
| 번호 | version_no, revision_no, lock_version, attempt는 정수. ID와 구분 |
| Time | UTC ISO 8601. 예: 2026-10-23T03:00:00Z |
| enum | ERD에 정의된 대문자 값. API별 추가 상태는 계약 초안 |
| 성공 | 리소스 또는 해당 API의 객체를 직접 반환. success/data 봉투를 추가하지 않음 |
| 선택 응답 속성 | 값이 없으면 생략. 빈 목록은 [] |
| PATCH | 생략은 유지. null 해제는 개별 필드에서 허용한 경우만. 하나 이상의 허용 필드 필요 |
| 읽기 기본값 | 문서 버전 생략은 현재 기준 설계의 승인본. 초안으로 자동 대체하지 않음 |
| 작성·승인자 | 클라이언트가 입력하지 않고 인증 주체에서 결정 |
| 요청 추적 | X-Request-Id를 응답하고 backend→ai까지 전달 |
| 비밀값 | 비밀번호·토큰 평문·개인 대화 원문·저장 경로는 로그·목록에서 제외 |

필드표의 Y는 필수, N은 선택이다. 조건부 필수는 설명에 명시한다. 객체 배열의 상세 필드는 타입 링크에서 확인한다. `content`·`unlinked`·`delta`의 미확정 구조는 임의 키를 영구 허용하는 계약이 아니라 Q01~03의 검토 대상이다.

### 성공·실패 예시

```json
{"id":"101","name":"BEEZ"}
```

```json
{"error":{"code":"version_conflict","message":"조회한 기준이 변경되었습니다. 최신 내용을 확인하세요."}}
```

204는 본문이 없다. 오류 본문은 기본 `error.code/message`다. AI 내부 실패의 선택적 usage는 [AI 문서](03-ai-internal.md)의 사용량 계약에 한정하며 웹으로 그대로 전달하지 않는다.

### 페이지

- 요청: `cursor?: string`, `limit?: integer`(기본 20, 최대 100).
- 응답: `items: T[]`, `has_more: boolean`, `next_cursor?: string`.
- 기본 정렬: `created_at DESC, id DESC`. cursor는 서버의 불투명 식별값이며 숫자 offset으로 해석하지 않는다.
- 권한·프로젝트 필터는 페이지·검색·집계 전에 적용한다. 후속 페이지도 같은 필터를 사용한다.
- 변경 Pull의 기준 번호는 모든 페이지를 읽기 전에 현재 최신 번호로 올리지 않는다.

## 4. HTTP·오류

| 상태 | 공통 의미 | 대표 code |
| --- | --- | --- |
| 200 | 조회·처리 성공 또는 요청 중 검증 완료 | — |
| 201 | 리소스 생성 | — |
| 202 | 처리 접수. 완료를 의미하지 않음 | — |
| 204 | 본문 없는 성공 | — |
| 400 | 형식·필수·업무 입력 오류 | invalid_json, validation_failed, invalid_transition |
| 401 | 로그인·에이전트·내부 인증 실패 | unauthenticated, token_revoked |
| 403 | 역할·소유 조건 미충족 | forbidden |
| 404 | 리소스 없음 또는 소속 비노출 | not_found |
| 405 | 지원하지 않는 메서드 | method_not_allowed |
| 409 | 현재 상태·버전·중복·의존 충돌 | version_conflict, revision_conflict, baseline_conflict |
| 410 | 링크 만료 | invitation_expired, reset_token_expired |
| 413 | 파일 용량 초과 | file_too_large |
| 415 | 본문·파일 형식 불가 | unsupported_media_type |
| 429 | 호출 제한 | rate_limited |
| 500 | 내부 오류 | internal |
| 502 | AI·모델 실패 | ai_unavailable, model_unavailable |
| 503 | 처리 기반 이용 불가 | processing_unavailable |
| 504 | 분석 시간 초과 | ai_timeout, model_timeout |

현재 backend의 공통 오류 응답과 snake_case 설정을 따른다. 신규 업무 code·페이지·문자열 ID는 구현 시 이 계약과 맞춘다. 검증이 NEEDS_INPUT/NEEDS_REVIEW/ERROR로 저장된 경우는 HTTP 전송 실패와 구분한다. 409 뒤에는 최신 리소스를 다시 조회한다.

## 5. 멱등성·동시성

- 변경 POST는 상세 Headers의 `Idempotency-Key`, MCP는 `idempotency_key`로 동일 동작을 식별한다. 키 범위는 인증 사용자·프로젝트·동작이다.
- 같은 키·같은 입력은 기존 결과를 반환하고 다른 입력은 409 idempotency_conflict다. 보존기간·저장 방식은 Q12다. 재요청도 현재 권한을 확인한다.
- 토큰 발급은 평문 1회 표시다. 평문 복원용 응답 캐시를 만들지 않는다. 응답을 잃으면 목록에서 발급분을 폐기하고 새로 발급한다.
- START는 같은 task_id·validation_id에 대해 중복 실행하지 않는다. 응답 유실을 새 작업 시작으로 처리하지 않는다.
- 작업 변경은 expected_revision_no/expected_lock_version, 제안 변경은 expected_updated_at, 초안 편집은 expected_version_id/expected_content_hash를 확인한다. Q08의 정밀도·해시 계약을 따른다.
- 시작·재개와 문서 발행은 프로젝트 잠금 아래 재검사한다. 모델 호출 중 DB 잠금을 잡고 기다리지 않는다.
- 공유 기록은 previous_id를 잇는 새 불변 리비전이다. 같은 이전 리비전의 두 번째 수정은 충돌이다.

## 6. 상태·버전·발행

| 대상 | 값·주요 조건 |
| --- | --- |
| 문서·설계 항목 | DRAFT → APPROVED → SUPERSEDED. REJECTED는 미채택. 승인 본문은 불변 |
| 변경 제안 | PROPOSED → IN_REVIEW → 승인·발행 → PUBLISHED 또는 REJECTED. APPROVED는 승인 처리 단계 |
| 작업 | DRAFT, READY, IN_PROGRESS, WAITING, COMPLETED, CANCELLED. READY는 계획 저장이며 PASS 보증 아님 |
| 검증 | NOT_RUN, RUNNING, PASS, NEEDS_INPUT, NEEDS_REVIEW, STALE, ERROR. NOT_RUN은 검증 부재 표시도 가능 |
| 대기 이유 | INPUT, APPROVAL, DEPENDENCY, REVALIDATION. WAITING에서만 포함. REVALIDATION은 시스템만 설정(설계 발행·완료 delta) |
| 문서 분석·초안 job | PENDING, RUNNING, COMPLETED, FAILED. 저장 구조·세부 정책 Q07. 검증 실행은 Validation.status·attempt로 따로 관리 |
| 현재 승인본 | project.current_baseline_id → baseline_item / baseline_document |
| 계획 참조 | 계획 리비전 × 항목 × 정확한 버전 × 관계 |
| 관계 강도 | CHANGES &gt; IMPLEMENTS &gt; READS. 항목당 하나, 팀 동의 Q02 |
| 완료 | 담당자의 자기 보고. 코드 품질·테스트 검증 완료와 구분 |

### 기준 설계 발행

초기 문서와 AI 생성 항목은 DRAFT다. 팀장이 검토한 원문·항목 버전을 승인하면 문서·항목 상태, baseline 두 스냅샷과 현재 포인터를 원자적으로 발행한다. 변경 제안 승인 시 제안의 base_baseline_id와 현재 baseline 전체가 같아야 한다. 달라지면 409이며 최신 기준으로 제안을 갱신해 검토한다.

변경 승인 이력·새 버전·baseline·제안 발행·관련 검증 STALE/진행 작업 WAITING 반영은 같은 트랜잭션이다. 실패하면 전체 롤백한다. 성공 커밋 뒤 알림·활동은 중복 식별값으로 처리한다. 이벤트 전달 실패는 이미 완료한 발행을 다시 생성하는 이유가 아니다. D7·전달 유실 방지 세부는 Q03이다.

### 검증·시작

START/RESUME는 현재 계획의 PASS, 실제 참조 항목 버전의 현재성, 선행 완료, CHANGES 점유 충돌을 검사한다. 작업 시작은 전역 baseline 번호만 달라졌다는 이유로 막지 않는다. 무관한 설계 변경으로 정상 PASS를 무효화하지 않는다.

변경 항목의 이전 버전을 참조한 검증은 STALE이고 진행 작업은 WAITING(REVALIDATION), 점유는 유지한다. 완료·취소 작업은 이력만 보존한다. AI 추론 후보만으로 확정 참조 무효화를 하지 않는다. 참조 의존 깊이는 Q02다.

판정은 backend 규칙이 내린다. AI는 finding 후보를 추가만 하고 규칙 finding을 해제하거나 심각도를 낮추지 못한다. 규칙만으로 NEEDS_INPUT/NEEDS_REVIEW가 확정되면 그 결과를 반환하고 검증 AI를 접수하지 않는다. 아래 AI 결과 매핑은 의미 분석이 필요해 RUNNING으로 접수한 실행에 적용한다.

| 상황 | 결과 | 판정 |
| --- | --- | --- |
| 필수 칸 누락, 모르는 항목·버전, CHANGES 없음 + no_contract_change=false + unlinked 비어 있음, no_contract_change=true인데 CHANGES 참조 있음 | NEEDS_INPUT | 규칙. 요청 안에서 확정 |
| 구조화된 계약의 명확한 불일치, 같은 항목을 CHANGES하는 IN_PROGRESS·WAITING 작업 존재 | NEEDS_REVIEW | 규칙. 요청 안에서 확정 |
| unlinked 등 의미 해석이 필요한 내용 | RUNNING → AI 분석 | backend가 필요한 검사 목록 `checks`를 고정한다. AI는 검사 수행 결과와 finding 후보만 반환 |
| 의미 분석을 완료했고 필수 계획 정보의 누락(`MISSING_INPUT`)만 남음 | NEEDS_INPUT | 규칙. AI가 발견한 경우에도 작업자가 보완. 필수 누락 후보는 BLOCKING |
| 의미 분석을 완료했고 설계 결정이 필요한 `MISSING_POLICY` 또는 BLOCKING 불일치·충돌 후보가 있음 | NEEDS_REVIEW | 규칙. 검토·설계 결정이 필요한 문제를 보완 요청과 함께 표시 |
| 위에 해당 없음, AI 분석이 불필요하거나 지정 범위 분석을 전부 완료 | PASS | 규칙. AI 경로는 COMPLETE, 검사 키의 완전한 대응, 출력·근거 검증을 모두 충족해야 함 |
| 분석 실패·시간 초과·출력 계약 위반·의미 분석의 일부 미검사(PARTIAL) | ERROR | 규칙. 자동 PASS 금지. 미검사 사유를 기록하고 보완된 입력은 새 검증으로 접수 |

S15P31A108-107의 검사 수행 범위·판정 매핑은 **변준성 합의 전 검토안**이다. `COMPLETE/PARTIAL`은 `/v1/validate` 결과의 수행 범위 표시이며 Validation.status나 문서 파싱·색인 job 상태에 새 enum을 추가하지 않는다.

- 최신성·실행 소유권 검사를 먼저 적용한다. 이미 STALE이거나 attempt·lease가 다른 실행에는 AI 결과·오류로 상태를 덮어쓰지 않는다.
- AI 응답의 `checked_keys`와 `unassessed[].check_key`는 요청 `checks[].key`를 누락·중복 없이 정확히 나눠야 한다. 잘못된 대응·근거 참조·잘린 출력은 분석 성공이 아니라 ERROR다. COMPLETE 표시 자체가 분석 품질이나 작업 코드의 정확성을 보증하지 않는다.
- `PARTIAL`은 모델 분석 후 근거 부족 또는 지원하지 못한 내용이 확인되어 필요한 검사를 수행하지 못한 경우다. backend는 현재 실패 실행에 `source=MODEL`, `kind=ERROR`, `resolution=HUMAN_REVIEW`인 finding을 만들고 미검사 사유와 필요한 근거 보완을 기록한다. 모델 실패·timeout·출력 계약 위반 및 BE 원문·검색 조회 장애, 워커 중단, lease 만료는 `source=MODEL`, `kind=ERROR`, `resolution=RETRY`로 구분한다. 이 ERROR finding은 backend가 생성하며 AI의 FindingCandidate 종류에 ERROR를 추가하지 않는다. 부분 AI 후보는 진단용이며 정상 분석 결과로 반영하지 않는다. 기존 규칙 finding은 유지한다. 실행 상태·실패 코드·원자적 종결은 [검증 실행 오류 규약](#validation-execution-errors)을 따른다.
- `MISSING_INPUT`은 작업자가 보완할 **필수 계획 정보**가 부족한 경우다. `MISSING_POLICY`는 원문에 결정되지 않았다고 명시되었거나, backend가 정한 정책 확인 범위에서 정책 부재를 확인한 경우에만 사용한다. 두 종류의 필수 누락 후보는 BLOCKING이다. 단순한 선택 정보 제안을 필수 누락으로 분류하지 않는다.
- 검색·전달된 근거에 정책이 없다는 사실만으로 `MISSING_POLICY`를 만들지 않는다. 근거가 충분하지 않으면 미검사 사유 `EVIDENCE_INSUFFICIENT`로 남긴다. backend의 원문·검색 조회 장애는 실행 ERROR이며 작업자의 입력 부족으로 바꾸지 않는다.
- 기존 규칙의 NEEDS_REVIEW는 유지한다. AI 후보에 검토 필요와 계획 보완이 함께 있으면 NEEDS_REVIEW를 우선하고 보완할 내용도 함께 표시한다. AI의 STALE_REF는 backend의 버전 재확인 대상이며, 후보만으로 확정 참조를 무효화하지 않는다.
- AI는 `related_task_id`를 입력 범위에서 참조한다. backend가 해당 작업의 현재 담당자를 확인해 후속 행동과 연결한다. AI가 멤버 ID를 생성하거나 담당자를 배정하지 않는다.

backend 워커는 attempt·lease 소유권을 확인해 RUNNING 결과만 반영한다. 같은 validation의 새 run으로 재시도할 수 있는 것은 **현재 실패 attempt의 ERROR finding이 RETRY인 경우**다. 현재 실행에 HUMAN_REVIEW가 하나라도 있으면 재시도를 거부하고 근거·지원 범위를 보완해 새 검증을 만든다. 과거 attempt의 RETRY finding을 재시도 근거로 쓰지 않는다. FE는 같은 resolution으로 버튼을 결정하고 REST·MCP도 이를 서버에서 검사한다. 현재 판정용 DTO의 ERROR finding은 current_attempt에 해당하는 실행의 것으로 한정하고 과거 실행 오류는 이력으로 구분한다. 입력·근거가 바뀌면 새 검증이며 STALE을 단순 retry로 되살리지 않는다. D3의 롤백은 검증·업무 상태 복귀이며 사용자의 소스 파일을 되돌리는 기능이 아니다.

<a id="validation-execution-errors"></a>

### 검증 실행 오류·상태 매핑

**`validation.status=ERROR`로 전이하는 모든 경로에서 backend는 현재 실패 attempt에 연결된 `source=MODEL`, `kind=ERROR`, `severity=BLOCKING` finding을 최소 하나 생성해야 한다.** `validation_run_id`는 필수이며, 해당 run의 validation_id와 attempt가 대상 검증의 현재 실행인지 BE가 검사한다. V2 DDL의 `source='RULE' OR validation_run_id IS NOT NULL` 제약을 적용받도록 ERROR finding에 RULE을 사용하지 않는다. 여기서 MODEL은 분석 실행 경로의 오류 분류이며 BE가 생성한 오류도 포함한다. 실제 모델 호출 여부는 origin/usage와 별도로 판단하며, 호출이 없으면 모델 응답이나 usage를 만들어내지 않는다.

의미 분석을 RUNNING으로 접수하면 BE는 현재 attempt를 할당하고 **원문·검색 조회 전에 해당 attempt의 MODEL run을 확보**한다. 복구 시 run이 없으면 같은 attempt의 실행 기록을 먼저 확보하고 finding을 연결한다. `UNIQUE(validation_id, attempt)`를 지키며 같은 attempt에 오류용 run을 추가 생성하지 않는다.

일반 워커는 현재 `RUNNING`·`current_attempt`·job/run lease 소유권을 확인하고, **run 종결·ERROR finding 생성·validation ERROR 전이·job 실패 정리**를 한 트랜잭션으로 반영한다. job은 실패 종결 시 lease 정보를 해제해 V2 제약을 만족시킨다. 기록 실패 시 상태 전이도 롤백한다. 워커 중단·lease 만료는 lease 회수 권한을 확보한 복구 주체가 잠금 또는 조건부 갱신으로 같은 검사를 수행해 종결하며, 사람이 retry를 요청하기 전에 RETRY finding을 남긴다. 복구가 같은 실패를 다시 관측해도 이미 종결된 run을 덮어쓰거나 finding을 중복 생성하지 않는다. 이미 STALE·새 attempt·다른 실행 소유권으로 넘어간 경우 늦은 결과로 현재 상태를 바꾸지 않는다.

아래는 **현재 유효한 실행**의 상태 매핑이다. HTTP 상태는 backend가 AI에서 받은 상태이며, 오류 finding은 모두 BE가 생성한다.

| 실행 결과·장애 | validation_run.status | Validation.status | ERROR finding resolution |
| --- | --- | --- | --- |
| 유효한 HTTP 200 COMPLETE, 출력·검사 범위·근거 검증 통과 | OK | 기존 규칙에 따라 PASS / NEEDS_INPUT / NEEDS_REVIEW | ERROR finding 없음 |
| AI HTTP 504 또는 BE→AI 호출 시간 초과 | TIMEOUT | ERROR | RETRY |
| AI HTTP 502·호출 실패 | ERROR | ERROR | RETRY |
| 잘린 출력·JSON·검사 키·ID·근거 등 출력 계약 위반 | ERROR | ERROR | RETRY |
| 유효한 HTTP 200 PARTIAL | ERROR | ERROR | HUMAN_REVIEW |
| BE 원문·검색 조회 장애 | ERROR | ERROR | RETRY |
| 워커 중단·lease 만료·그 밖의 BE 실행 오류 | ERROR | ERROR | RETRY |
| 이전 attempt/lease의 늦은 결과, 이미 STALE 등으로 무효화 | 해당 run이 아직 RUNNING이면 DISCARDED, 이미 종결됐으면 유지 | 현재 상태 유지 | 현재 실행의 ERROR finding을 만들지 않음 |

만료된 **현재** 실행을 회수해 ERROR/RETRY로 종결하는 경로와, 그 뒤 도착한 예전 워커 응답을 폐기하는 경로를 구분한다. run의 OK는 분석 수행 성공이며 검증 PASS와 다르다. PARTIAL은 HTTP 200이어도 run.ERROR이고, 부분 후보를 정상 결과로 반영하지 않는다.

`Validation.error_code`는 **ERROR일 때 필수이고 다른 상태에서는 생략하는 DTO 필드**다. BE가 현재 실패 attempt의 정규화된 오류 분류에서 아래 코드 하나를 조립한다. DB의 validation 컬럼을 뜻하지 않으며 상세 저장 키·summary/run/finding 매핑은 -159에서 확정한다. 사람이 읽는 `validation_run.error` 문장을 임의로 파싱해 코드를 추측하지 않는다. 재시도 허용 여부는 이 코드 대신 현재 ERROR finding의 `resolution`으로 결정한다.

| Validation.error_code | 조건 | ERROR finding resolution |
| --- | --- | --- |
| `ai_evidence_insufficient` | PARTIAL의 미검사 사유가 EVIDENCE_INSUFFICIENT만 있음 | HUMAN_REVIEW |
| `ai_unsupported_content` | PARTIAL의 미검사 사유가 UNSUPPORTED_CONTENT만 있음 | HUMAN_REVIEW |
| `ai_analysis_incomplete` | PARTIAL에 위 두 미검사 사유가 함께 있음 | HUMAN_REVIEW |
| `ai_timeout` | AI HTTP 504 또는 BE→AI 호출 시간 초과 | RETRY |
| `ai_unavailable` | AI HTTP 502·호출 실패 | RETRY |
| `ai_invalid_output` | BE가 직접 확인한 잘린 응답·형식·검사 키·ID·근거 등 출력 계약 위반 | RETRY |
| `evidence_fetch_failed` | BE 원문·검색 조회 장애 | RETRY |
| `validation_worker_interrupted` | 복구 주체가 확인한 워커 중단 | RETRY |
| `validation_lease_expired` | 현재 실행 lease 만료 회수 | RETRY |
| `validation_execution_failed` | 위 항목으로 분류되지 않는 BE 실행 오류 | RETRY |

한 실행에서 여러 장애가 관측되면 원자적 종결에 성공한 실패 원인의 코드 하나를 사용한다. 후속·늦은 결과가 코드를 바꾸지 않으며, PARTIAL의 여러 미검사 사유는 위 표의 단독/혼합 규칙으로 분류한다. AI가 내부 출력 오류를 이미 HTTP 502/model_unavailable로 정규화해 반환한 경우 DTO는 ai_unavailable이다. BE가 받은 응답의 계약 위반을 직접 확인했을 때만 ai_invalid_output으로 분류하며 오류 메시지 문장으로 세부 코드를 추측하지 않는다. AI HTTP 오류의 `error.code`와 웹·MCP의 전송 오류 `error.code`는 각각 기존 전송 계약을 유지한다.

## 7. 책임·공통 서비스

| 기능 | 서버·AI 담당 | 화면·연결 담당 |
| --- | --- | --- |
| 계정·프로젝트·팀·연결 | 전진 | 박민용 |
| 작업·검증·관계·비용 조회 | 변준성 / 분석 AI 양지훈 | 박민용 / 검증 상태관리 양지훈 |
| 문서·기획·변경·승인 | 오서현 / 파싱·검색·생성 AI 박상은 / 영향 AI 양지훈 | 오서현 |
| 비비 대화·역할 추천 | 박상은 / 문서 BE 오서현·배정 BE 변준성 연결 검토 | 박상은 |
| 회의 | 오서현 BE / 양지훈 AI / 수정 모듈 박상은 | 오서현 |
| MCP | 전진 진입점·인증·감사 / 각 업무 서비스 소유자 | 전진 도구 연결 |
| 알림·활동 | 도메인이 대상·내용 결정 / 전진 저장·중복 방지·조회 | 박민용 |
| 대시보드 | 각 도메인 소유 조회 API | 박민용이 화면에서 조합 |
| 공통 LLM | 양지훈 공통 호출·오류·출처·사용량 / 각 AI 담당 프롬프트·검증 | 각 기능 담당 |

대시보드의 문서·변경 현황은 오서현, 작업·검증·관계·비용은 변준성, 연결·타임라인은 전진이 제공한다. 에이전트의 연결과 현재 작업은 별도 API로 조회한다. API별 세부 책임은 아래 목록에 표기한다.

### 공통 서비스 데이터

아래는 도메인 서비스 간 전달 계약이다. 별도 공개 REST endpoint나 메시지 브로커를 의미하지 않는다.

| 전달 정보 | 필수 필드 | 선택·조건 |
| --- | --- | --- |
| 활동 이벤트 | event_key, project_id, actor_type, type, target_type, target_id, occurred_at | 사람·에이전트 행위에는 actor_account_id 필수. 시스템 행위자는 actor_type=BEE 등으로 식별하고 계정 ID 생략 |
| 알림 요청 | notification_key, project_id, recipient_account_id, type, title, body, target_type, target_id | 개인별 읽음 시각은 알림 서비스에서 관리 |
| LLM 호출 사용량 | request_key, feature, project_id, model, failed, latency_ms, tokens_known, caller | task_id, validation_id, attempt, input_tokens, output_tokens, estimated_cost_amount, currency. caller는 AGENT·WEB이고 AGENT면 agent_session_id 필수 |
| 검증 실행 기록 | validation_id, plan_revision_id, attempt, analyzer_version, latency_ms, cache_hit | usage_request_keys, error_code |

비용 조회는 실제 모델 호출 식별값으로 중복 제거한다. 작업 미연결 문서 분석·대화도 프로젝트·기능별 합계에 포함한다. 공급자 사용량을 모르면 unknown을 보존하고 추정 비용과 실제 청구값을 혼용하지 않는다.

## 8. API 목록

### Backend REST

| 명세 ID·상세 | Method / Path | 권한 | BE | AI | FE |
| --- | --- | --- | --- | --- | --- |
| [ACC-A01 회원가입](01-backend-rest.md#acc-a01) | POST `/api/auth/register` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A02 로그인](01-backend-rest.md#acc-a02) | POST `/api/auth/login` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A03 로그아웃](01-backend-rest.md#acc-a03) | POST `/api/auth/logout` | USER | 전진 | — | 박민용 |
| [ACC-A04 내 계정 조회](01-backend-rest.md#acc-a04) | GET `/api/me` | USER | 전진 | — | 박민용 |
| [ACC-A05 프로필 수정](01-backend-rest.md#acc-a05) | PATCH `/api/me` | USER | 전진 | — | 박민용 |
| [ACC-A06 비밀번호 변경](01-backend-rest.md#acc-a06) | PUT `/api/me/password` | USER | 전진 | — | 박민용 |
| [ACC-A07 임시 비밀번호 발급](01-backend-rest.md#acc-a07) | POST `/api/auth/password/forgot` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A08 재설정 링크 확인 (폐지 예정)](01-backend-rest.md#acc-a08) | POST `/api/auth/password/reset/verify` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A09 비밀번호 재설정 (폐지 예정)](01-backend-rest.md#acc-a09) | POST `/api/auth/password/reset` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A10 회원 탈퇴](01-backend-rest.md#acc-a10) | POST `/api/me/withdraw` | USER | 전진 | — | 박민용 |
| [ACC-A11 가입 인증 코드 보내기](01-backend-rest.md#acc-a11) | POST `/api/auth/email-verifications` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A12 가입 인증 코드 확인](01-backend-rest.md#acc-a12) | POST `/api/auth/email-verifications/{verification_id}/confirm` | PUBLIC | 전진 | — | 박민용 |
| [ACC-A13 탈퇴 가능 여부](01-backend-rest.md#acc-a13) | GET `/api/me/withdrawal-check` | USER | 전진 | — | 박민용 |
| [PRJ-A01 프로젝트 생성](01-backend-rest.md#prj-a01) | POST `/api/projects` | USER | 전진 | — | 박민용 |
| [PRJ-A02 참여 프로젝트 목록](01-backend-rest.md#prj-a02) | GET `/api/projects` | USER | 전진 | — | 박민용 |
| [PRJ-A03 프로젝트 상세](01-backend-rest.md#prj-a03) | GET `/api/projects/{id}` | MEMBER | 전진 | — | 박민용 |
| [PRJ-A04 프로젝트 정보 수정](01-backend-rest.md#prj-a04) | PATCH `/api/projects/{id}` | LEADER | 전진 | — | 박민용 |
| [PRJ-A05 프로젝트 삭제](01-backend-rest.md#prj-a05) | DELETE `/api/projects/{id}` | LEADER | 전진 | — | 박민용 |
| [PRJ-A06 초기 설정 현황](01-backend-rest.md#prj-a06) | GET `/api/projects/{id}/onboarding` | MEMBER | 전진 | — | 박민용 |
| [MEM-A01 팀원 초대 생성](01-backend-rest.md#mem-a01) | POST `/api/projects/{id}/invitations` | LEADER | 전진 | — | 박민용 |
| [MEM-A02 초대 목록](01-backend-rest.md#mem-a02) | GET `/api/projects/{id}/invitations` | LEADER | 전진 | — | 박민용 |
| [MEM-A03 초대 폐기](01-backend-rest.md#mem-a03) | DELETE `/api/projects/{id}/invitations/{invitation_id}` | LEADER | 전진 | — | 박민용 |
| [MEM-A04 초대 미리보기](01-backend-rest.md#mem-a04) | POST `/api/invitations/preview` | PUBLIC | 전진 | — | 박민용 |
| [MEM-A05 초대 수락](01-backend-rest.md#mem-a05) | POST `/api/invitations/accept` | USER | 전진 | — | 박민용 |
| [MEM-A06 팀원 목록](01-backend-rest.md#mem-a06) | GET `/api/projects/{id}/members` | MEMBER | 전진 | — | 박민용 |
| [MEM-A07 팀원 담당 영역·표시 수정](01-backend-rest.md#mem-a07) | PATCH `/api/projects/{id}/members/{member_id}` | LEADER | 전진 | — | 박민용 |
| [MEM-A08 팀장 위임](01-backend-rest.md#mem-a08) | POST `/api/projects/{id}/leadership-transfer` | LEADER | 전진 | — | 박민용 |
| [MEM-A09 팀원 제외](01-backend-rest.md#mem-a09) | POST `/api/projects/{id}/members/{member_id}/remove` | LEADER | 전진 | — | 박민용 |
| [MEM-A10 프로젝트 나가기](01-backend-rest.md#mem-a10) | POST `/api/projects/{id}/leave` | MEMBER | 전진 | — | 박민용 |
| [MEM-A11 초대 다시 보내기](01-backend-rest.md#mem-a11) | POST `/api/projects/{id}/invitations/{invitation_id}/resend` | LEADER | 전진 | — | 박민용 |
| [AGT-A01 에이전트 토큰 발급](01-backend-rest.md#agt-a01) | POST `/api/projects/{id}/agent-tokens` | MEMBER, 본인 소유 객체 | 전진 | — | 박민용 |
| [AGT-A02 내 에이전트 토큰 목록](01-backend-rest.md#agt-a02) | GET `/api/me/agent-tokens` | USER | 전진 | — | 박민용 |
| [AGT-A03 에이전트 토큰 폐기](01-backend-rest.md#agt-a03) | DELETE `/api/agent-tokens/{id}` | USER, 본인 소유 객체 | 전진 | — | 박민용 |
| [AGT-A04 에이전트 연결 안내](01-backend-rest.md#agt-a04) | GET `/api/projects/{id}/agent-connection-guide` | MEMBER | 전진 | — | 박민용 |
| [AGT-A05 에이전트 연결 현황 목록](01-backend-rest.md#agt-a05) | GET `/api/projects/{id}/agents` | MEMBER | 전진 | — | 박민용 |
| [AGT-A06 에이전트 연결 상세](01-backend-rest.md#agt-a06) | GET `/api/projects/{id}/agents/{agent_session_id}` | MEMBER | 전진 | — | 박민용 |
| [DOC-A01 초기 문서 업로드](01-backend-rest.md#doc-a01) | POST `/api/projects/{id}/documents` | MEMBER (수정안 요청, 지금 LEADER) | 오서현 | 박상은 | 오서현 |
| [DOC-A02 문서 목록](01-backend-rest.md#doc-a02) | GET `/api/projects/{id}/documents` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A03 문서 원문 조회](01-backend-rest.md#doc-a03) | GET `/api/projects/{id}/documents/{document_id}` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A04 원본 파일 다운로드](01-backend-rest.md#doc-a04) | GET `/api/projects/{id}/documents/{document_id}/versions/{version_no}/file` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A05 문서 버전 목록](01-backend-rest.md#doc-a05) | GET `/api/projects/{id}/documents/{document_id}/versions` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A06 문서 버전 비교](01-backend-rest.md#doc-a06) | GET `/api/projects/{id}/documents/{document_id}/diff` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A07 문서 분류 수정](01-backend-rest.md#doc-a07) | PATCH `/api/projects/{id}/documents/{document_id}` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A01 문서 분석 상태·결과 조회](01-backend-rest.md#pln-a01) | GET `/api/projects/{id}/documents/{document_id}/analysis` | MEMBER | 오서현 | 박상은 | 오서현 |
| [PLN-A02 문서 분석 재시도](01-backend-rest.md#pln-a02) | POST `/api/projects/{id}/documents/{document_id}/analysis/retry` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A03 기획 보완 질문 목록](01-backend-rest.md#pln-a03) | GET `/api/projects/{id}/planning-questions` | MEMBER | 오서현 | 박상은 | 오서현 |
| [PLN-A04 기획 보완 질문 답변](01-backend-rest.md#pln-a04) | PATCH `/api/projects/{id}/planning-questions/{question_id}` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A05 답변을 반영한 설계 초안 생성](01-backend-rest.md#pln-a05) | POST `/api/projects/{id}/design-drafts` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A06 설계 항목 목록](01-backend-rest.md#pln-a06) | GET `/api/projects/{id}/design-items` | MEMBER | 오서현 | 박상은 | 오서현 |
| [PLN-A07 설계 항목 초안 추가](01-backend-rest.md#pln-a07) | POST `/api/projects/{id}/design-items` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A08 설계 항목 초안 수정](01-backend-rest.md#pln-a08) | PATCH `/api/human/design-items/{id}` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A09 설계 항목 초안 제외](01-backend-rest.md#pln-a09) | DELETE `/api/human/design-items/{id}` | LEADER | 오서현 | 박상은 | 오서현 |
| [PLN-A10 최초 기준 설계 승인·발행](01-backend-rest.md#pln-a10) | POST `/api/human/baselines` | LEADER | 오서현 | 박상은 | 오서현 |
| [DOC-A08 현재 기준 설계 조회](01-backend-rest.md#doc-a08) | GET `/api/projects/{id}/baselines/current` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A09 기준 설계 이력 목록](01-backend-rest.md#doc-a09) | GET `/api/projects/{id}/baselines` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A10 기준 설계 특정 버전 조회](01-backend-rest.md#doc-a10) | GET `/api/projects/{id}/baselines/{version_no}` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A11 문서·설계 검색](01-backend-rest.md#doc-a11) | GET `/api/projects/{id}/search` | MEMBER | 오서현 | 박상은 | 오서현 |
| [TRC-A01 설계 항목 의존관계 조회](01-backend-rest.md#trc-a01) | GET `/api/projects/{id}/design-items/{item_id}/dependencies` | MEMBER | 변준성 | — | 박민용 |
| [DOC-A12 변경용 원문 파일 업로드](01-backend-rest.md#doc-a12) | POST `/api/projects/{id}/documents/{document_id}/draft-versions` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A13 비비 검토 상태](01-backend-rest.md#doc-a13) | GET `/api/projects/{id}/document-versions/{version_id}/review` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A14 비비 검토 재시도](01-backend-rest.md#doc-a14) | POST `/api/projects/{id}/document-versions/{version_id}/review/retry` | MEMBER (등록자) | 오서현 | 박상은 | 오서현 |
| [DOC-A15 비비 수정 제안 목록](01-backend-rest.md#doc-a15) | GET `/api/projects/{id}/document-versions/{version_id}/review/suggestions` | MEMBER | 오서현 | 박상은 | 오서현 |
| [DOC-A16 수정 제안 반영·넘기기](01-backend-rest.md#doc-a16) | PATCH `/api/projects/{id}/document-versions/{version_id}/review/suggestions/{suggestion_id}` | MEMBER (등록자) | 오서현 | 박상은 | 오서현 |
| [DOC-A17 수정 제안에 대해 비비와 대화](01-backend-rest.md#doc-a17) | POST `/api/projects/{id}/document-versions/{version_id}/review/suggestions/{suggestion_id}/messages` | MEMBER (등록자) | 오서현 | 박상은 | 오서현 |
| [DOC-A18 비비에게 직접 수정 요청](01-backend-rest.md#doc-a18) | POST `/api/projects/{id}/document-versions/{version_id}/review/requests` | MEMBER (등록자) | 오서현 | 박상은 | 오서현 |
| [DOC-A19 팀장 승인 요청](01-backend-rest.md#doc-a19) | POST `/api/projects/{id}/document-versions/{version_id}/approval-request` | MEMBER (등록자) | 오서현 | — | 오서현 |
| [DOC-A20 승인 요청 취소](01-backend-rest.md#doc-a20) | DELETE `/api/projects/{id}/document-versions/{version_id}/approval-request` | MEMBER (등록자) | 오서현 | — | 오서현 |
| [DOC-A21 문서 버전 승인](01-backend-rest.md#doc-a21) | POST `/api/projects/{id}/document-versions/{version_id}/approve` | LEADER | 오서현 | — | 오서현 |
| [DOC-A22 문서 버전 반려](01-backend-rest.md#doc-a22) | POST `/api/projects/{id}/document-versions/{version_id}/reject` | LEADER | 오서현 | — | 오서현 |
| [DOC-A23 반려 후 비비 재검토](01-backend-rest.md#doc-a23) | POST `/api/projects/{id}/document-versions/{version_id}/review/rerun` | MEMBER (등록자) | 오서현 | 박상은 | 오서현 |
| [CHG-A01 설계 변경 제안 생성](01-backend-rest.md#chg-a01) | POST `/api/projects/{id}/change-proposals` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A02 설계 변경 제안 목록](01-backend-rest.md#chg-a02) | GET `/api/projects/{id}/change-proposals` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A03 설계 변경 제안 상세·비교](01-backend-rest.md#chg-a03) | GET `/api/projects/{id}/change-proposals/{proposal_id}` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A04 설계 변경 제안 갱신·재제출](01-backend-rest.md#chg-a04) | PUT `/api/projects/{id}/change-proposals/{proposal_id}` | MEMBER (제안자) | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A05 변경 영향 조회](01-backend-rest.md#chg-a05) | GET `/api/projects/{id}/change-proposals/{proposal_id}/impact` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A06 변경 영향 AI 재분석](01-backend-rest.md#chg-a06) | POST `/api/projects/{id}/change-proposals/{proposal_id}/impact/retry` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A07 문서 수정 초안 생성](01-backend-rest.md#chg-a07) | POST `/api/projects/{id}/change-drafts` | MEMBER | 오서현 | 박상은 | 오서현 |
| [CHG-A08 변경 제안 검토 시작](01-backend-rest.md#chg-a08) | POST `/api/projects/{id}/change-proposals/{proposal_id}/review` | LEADER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A09 변경 제안 승인·발행](01-backend-rest.md#chg-a09) | POST `/api/projects/{id}/change-proposals/{proposal_id}/approve` | LEADER | 오서현 / 검증 영향: 변준성 | — | 오서현 |
| [CHG-A10 변경 제안 반려](01-backend-rest.md#chg-a10) | POST `/api/projects/{id}/change-proposals/{proposal_id}/reject` | LEADER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A11 승인 이력 조회](01-backend-rest.md#chg-a11) | GET `/api/projects/{id}/change-proposals/{proposal_id}/approval` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A12 변경 제안 의견·검토 요청 등록](01-backend-rest.md#chg-a12) | POST `/api/projects/{id}/change-proposals/{proposal_id}/comments` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A13 변경 제안 의견 목록](01-backend-rest.md#chg-a13) | GET `/api/projects/{id}/change-proposals/{proposal_id}/comments` | MEMBER | 오서현 | 양지훈·박상은 | 오서현 |
| [CHG-A14 승인 미리 보기](01-backend-rest.md#chg-a14) | GET `/api/projects/{id}/change-proposals/{proposal_id}/approval-preview` | LEADER | 오서현 / 검증 영향: 변준성 | — | 오서현 |
| [CHG-A15 변경 제안 거두기](01-backend-rest.md#chg-a15) | POST `/api/projects/{id}/change-proposals/{proposal_id}/withdraw` | MEMBER (제안자) | 오서현 | — | 오서현 |
| [TSK-A01 작업 계획 등록](01-backend-rest.md#tsk-a01) | POST `/api/projects/{id}/tasks` | MEMBER | 변준성 | — | 박민용 |
| [TSK-A02 작업 목록](01-backend-rest.md#tsk-a02) | GET `/api/projects/{id}/tasks` | MEMBER | 변준성 | — | 박민용 |
| [TSK-A03 작업 상세](01-backend-rest.md#tsk-a03) | GET `/api/projects/{id}/tasks/{task_id}` | MEMBER | 변준성 | — | 박민용 |
| [TSK-A04 작업 계획 새 리비전 저장](01-backend-rest.md#tsk-a04) | POST `/api/projects/{id}/tasks/{task_id}/plan-revisions` | MEMBER + TASK_OWNER | 변준성 | — | 박민용 |
| [TSK-A05 작업 계획 리비전 이력](01-backend-rest.md#tsk-a05) | GET `/api/projects/{id}/tasks/{task_id}/plan-revisions` | MEMBER | 변준성 | — | 박민용 |
| [TSK-A06 작업 담당자 변경](01-backend-rest.md#tsk-a06) | PATCH `/api/projects/{id}/tasks/{task_id}/assignee` | LEADER | 변준성 | — | 박민용 |
| [VAL-A01 작업 검증 접수](01-backend-rest.md#val-a01) | POST `/api/projects/{id}/tasks/{task_id}/validations` | MEMBER + TASK_OWNER | 변준성 | 양지훈 | 박민용 |
| [VAL-A02 검증 결과 조회](01-backend-rest.md#val-a02) | GET `/api/projects/{id}/validations/{validation_id}` | MEMBER | 변준성 | 양지훈 | 박민용 |
| [VAL-A03 작업 검증 이력](01-backend-rest.md#val-a03) | GET `/api/projects/{id}/tasks/{task_id}/validations` | MEMBER | 변준성 | 양지훈 | 박민용 |
| [VAL-A04 검증 실행 재시도](01-backend-rest.md#val-a04) | POST `/api/projects/{id}/validations/{validation_id}/retry` | MEMBER + TASK_OWNER | 변준성 | 양지훈 | 박민용 |
| [TSK-A07 작업 상태 보고](01-backend-rest.md#tsk-a07) | POST `/api/projects/{id}/tasks/{task_id}/state-events` | MEMBER + TASK_OWNER | 변준성 | — | 박민용 |
| [CTX-A01 개발 컨텍스트 패킷 조회](01-backend-rest.md#ctx-a01) | GET `/api/projects/{id}/context-packets/{packet_id}` | MEMBER | 변준성 | — | 박민용 |
| [CTX-A02 공유 작업 기록 검색](01-backend-rest.md#ctx-a02) | GET `/api/projects/{id}/context-records` | MEMBER | 변준성 | — | 박민용 |
| [CTX-A03 작업 기록 상세](01-backend-rest.md#ctx-a03) | GET `/api/projects/{id}/context-records/{record_id}` | MEMBER / RECORD_AUTHOR | 변준성 | — | 박민용 |
| [CTX-A04 공유 작업 기록 생성·리비전 추가](01-backend-rest.md#ctx-a04) | POST `/api/projects/{id}/context-records` | MEMBER | 변준성(저장·조회), 전진(MCP 계약) | 박상은(검색·맥락) | 박민용 |
| [CTX-A05 작업 설계 근거 추천](01-backend-rest.md#ctx-a05) | POST `/api/projects/{id}/design-reference-suggestions` | MEMBER | 변준성 | 양지훈(근거 추천) / 박상은(검색) | 박민용 |
| [CTX-A06 컨텍스트 조회 이력](01-backend-rest.md#ctx-a06) | GET `/api/projects/{id}/context-access-logs` | LEADER | 변준성 | — | 박민용 |
| [BEE-A01 비비에게 질문](01-backend-rest.md#bee-a01) | POST `/api/projects/{id}/bee/questions` | MEMBER | 오서현(문서 조회·생성 연결) | 박상은 | 박상은 |
| [BEE-A02 작업 역할 분담 추천](01-backend-rest.md#bee-a02) | POST `/api/projects/{id}/assignment-suggestions` | MEMBER | 변준성 | 박상은 | 박상은 |
| [BEE-A03 비비 답변 피드백](01-backend-rest.md#bee-a03) | POST `/api/projects/{id}/bee/answers/{answer_id}/feedback` | MEMBER | 오서현 | 박상은 | 박상은 |
| [BEE-A04 비비 현재 상태](01-backend-rest.md#bee-a04) | GET `/api/projects/{id}/bee/status` | MEMBER | 오서현 | — | 박민용 |
| [MTG-A01 회의 생성 (폐지 예정)](01-backend-rest.md#mtg-a01) | POST `/api/projects/{id}/meetings` | MEMBER | 오서현 | 양지훈 | 오서현 |
| [MTG-A02 회의 목록](01-backend-rest.md#mtg-a02) | GET `/api/projects/{id}/meetings` | MEMBER | 오서현 | 양지훈 | 오서현 |
| [MTG-A03 회의 상세](01-backend-rest.md#mtg-a03) | GET `/api/projects/{id}/meetings/{meeting_id}` | MEMBER | 오서현 | 양지훈 | 오서현 |
| [MTG-A04 회의 참여 요청 (폐지 예정)](01-backend-rest.md#mtg-a04) | POST `/api/projects/{id}/meetings/{meeting_id}/participants` | MEMBER | 오서현 | 양지훈 | 오서현 |
| [MTG-A05 회의 참여 응답 (폐지 예정)](01-backend-rest.md#mtg-a05) | PATCH `/api/projects/{id}/meetings/{meeting_id}/participation` | MEMBER (초대 대상 본인) | 오서현 | 양지훈 | 오서현 |
| [MTG-A06 회의 텍스트 기록](01-backend-rest.md#mtg-a06) | POST `/api/projects/{id}/meetings/{meeting_id}/entries` | MEMBER (회의 참여자) | 오서현 | 양지훈 | 오서현 |
| [MTG-A07 비비의 회의 맥락 분석 (폐지 예정)](01-backend-rest.md#mtg-a07) | POST `/api/projects/{id}/meetings/{meeting_id}/bee-analysis` | MEMBER (회의 참여자) | 오서현 | 양지훈 | 오서현 |
| [MTG-A08 회의록 생성 (폐지 예정)](01-backend-rest.md#mtg-a08) | POST `/api/projects/{id}/meetings/{meeting_id}/minutes` | MEMBER (회의 참여자) | 오서현 | 양지훈 | 오서현 |
| [MTG-A09 회의 결정 기반 문서 수정안 (폐지 예정)](01-backend-rest.md#mtg-a09) | POST `/api/projects/{id}/meetings/{meeting_id}/change-drafts` | MEMBER (회의 참여자) | 오서현 | 양지훈(회의 연결) / 박상은(수정안 생성) | 오서현 |
| [MTG-A10 회의 기록 목록](01-backend-rest.md#mtg-a10) | GET `/api/projects/{id}/meetings/{meeting_id}/entries` | MEMBER | 오서현 | — | 오서현 |
| [MTG-A11 비비 회의 정리](01-backend-rest.md#mtg-a11) | POST `/api/projects/{id}/meetings/{meeting_id}/bee-summary` | LEADER (회의 참여자) | 오서현 | 양지훈 | 오서현 |
| [MTG-A12 회의 정리 수정 요청](01-backend-rest.md#mtg-a12) | POST `/api/projects/{id}/meetings/{meeting_id}/bee-summary/{summary_id}/revise` | LEADER (회의 참여자) | 오서현 | 양지훈 | 오서현 |
| [MTG-A13 회의 끝내기](01-backend-rest.md#mtg-a13) | POST `/api/projects/{id}/meetings/{meeting_id}/close` | LEADER (회의 참여자) | 오서현 | 박상은(문서 반영) | 오서현 |
| [MON-D01 문서 승인 현황](01-backend-rest.md#mon-d01) | GET `/api/projects/{id}/documents/summary` | MEMBER | 오서현 | — | 박민용 |
| [MON-C01 변경 제안 현황](01-backend-rest.md#mon-c01) | GET `/api/projects/{id}/change-proposals/summary` | MEMBER | 오서현 | — | 박민용 |
| [MON-T01 작업 상태 현황](01-backend-rest.md#mon-t01) | GET `/api/projects/{id}/tasks/summary` | MEMBER | 변준성 | — | 박민용 |
| [MON-V01 검증 상태 현황](01-backend-rest.md#mon-v01) | GET `/api/projects/{id}/validations/summary` | MEMBER | 변준성 | — | 박민용 |
| [MON-A02 프로젝트 AI 사용량·추정 비용](01-backend-rest.md#mon-a02) | GET `/api/projects/{id}/usage` | MEMBER | 변준성 | — | 박민용 |
| [MON-A03 작업·설계·검증 관계](01-backend-rest.md#mon-a03) | GET `/api/projects/{id}/relations` | MEMBER | 변준성 | — | 박민용 |
| [MON-A04 프로젝트 활동 타임라인](01-backend-rest.md#mon-a04) | GET `/api/projects/{id}/activities` | MEMBER | 전진 | — | 박민용 |
| [MON-A05 에이전트 현재 작업 현황](01-backend-rest.md#mon-a05) | GET `/api/projects/{id}/agents/work-status` | MEMBER | 변준성 | — | 박민용 |
| [MON-A06 내 접속 상태 보고](01-backend-rest.md#mon-a06) | PUT `/api/projects/{id}/presence` | MEMBER | 전진 | — | 박민용 |
| [MON-A07 팀원 접속 현황](01-backend-rest.md#mon-a07) | GET `/api/projects/{id}/presence` | MEMBER | 전진 | — | 박민용 |
| [NTF-A01 내 알림함](01-backend-rest.md#ntf-a01) | GET `/api/me/notifications` | USER | 전진 | — | 박민용 |
| [NTF-A02 알림 읽음 처리](01-backend-rest.md#ntf-a02) | POST `/api/me/notifications/read` | USER | 전진 | — | 박민용 |
| [SYS-A01 backend 기동 확인](01-backend-rest.md#sys-a01) | GET `/api/ping` | PUBLIC | 전진 | — | — |
| [SYS-A02 backend·AI 연결 확인](01-backend-rest.md#sys-a02) | GET `/api/system/status` | USER | 전진 | — | — |

### MCP

| 명세 ID·상세 | 도구 | 업무 서비스·연결 | AI |
| --- | --- | --- | --- |
| [MCP-01](02-mcp-tools.md#mcp-01) | `get_development_context` | 전진(도구) / 업무 저장: 변준성 | 박상은(검색·의존 설계·JSON) |
| [MCP-02](02-mcp-tools.md#mcp-02) | `get_document` | 전진(도구) / 오서현(문서) | — |
| [MCP-03](02-mcp-tools.md#mcp-03) | `get_design_changes` | 전진(도구) / 변준성(변경·최신성) | — |
| [MCP-04](02-mcp-tools.md#mcp-04) | `propose_design_change` | 전진(도구) / 오서현(제안) | 양지훈(영향 분석) |
| [MCP-05](02-mcp-tools.md#mcp-05) | `find_context` | 전진(도구 계약) / 변준성(최소 저장·조회) | 박상은(검색) |
| [MCP-06](02-mcp-tools.md#mcp-06) | `read_context` | 전진(도구) / 변준성(업무) | — |
| [MCP-07](02-mcp-tools.md#mcp-07) | `write_context` | 전진(도구 계약) / 변준성(업무) | 박상은(맥락 정책) |
| [MCP-10](02-mcp-tools.md#mcp-10) | `upsert_task_plan` | 전진(도구) / 변준성(업무) | — |
| [MCP-11](02-mcp-tools.md#mcp-11) | `validate_task_plan` | 전진(도구) / 변준성(실행·상태·저장) | 양지훈(분석) |
| [MCP-13](02-mcp-tools.md#mcp-13) | `get_validation` | 전진(도구) / 변준성(조회) | — |
| [MCP-14](02-mcp-tools.md#mcp-14) | `retry_validation` | 전진(도구) / 변준성(재시도) | 양지훈(실패 정보) |
| [MCP-12](02-mcp-tools.md#mcp-12) | `report_task_state` | 전진(도구) / 변준성(전이·점유) | — |

### AI Internal

| 명세 ID·상세 | Method / Path | 호출 BE | AI |
| --- | --- | --- | --- |
| [AI-A01 문서 파싱·설계 항목 추출](03-ai-internal.md#ai-a01) | POST `/v1/parse` | 오서현 | 박상은 |
| [AI-A02 작업 계획 의미 분석](03-ai-internal.md#ai-a02) | POST `/v1/validate` | 변준성 | 양지훈 |
| [AI-A03 변경 영향·담당자 분석](03-ai-internal.md#ai-a03) | POST `/v1/impact` | 오서현 / 작업 조회: 변준성 | 양지훈 |
| [AI-A04 텍스트 임베딩](03-ai-internal.md#ai-a04) | POST `/v1/embeddings` | 오서현(문서 접근) / 변준성(맥락 연계) | 박상은 |
| [문서 청킹](03-ai-internal.md#document-chunks) | POST `/v1/document-chunks` | 오서현(문서 접근) | 박상은 |
| [AI-A05 비비 설계 질의 응답](03-ai-internal.md#ai-a05) | POST `/v1/bee/chat` | 오서현(문서 연결) | 박상은 |
| [AI-A06 기획·문서 수정 초안 생성](03-ai-internal.md#ai-a06) | POST `/v1/bee/draft` | 오서현 | 박상은 / 회의 연결: 양지훈 |
| [AI-A07 작업 담당 후보 추천](03-ai-internal.md#ai-a07) | POST `/v1/bee/assignments` | 변준성 | 박상은 |
| [AI-A08 회의 질문·요약·결정 추출](03-ai-internal.md#ai-a08) | POST `/v1/bee/meeting-analysis` | 오서현 | 양지훈 |
| [AI-A10 문서 검토·수정 제안](03-ai-internal.md#ai-a10) | POST `/v1/bee/document-review` | 오서현 | 박상은 |

### 확장 후보

| 요구사항 | 목적 | 예상 계약 | 담당 |
| --- | --- | --- | --- |
| GIT-01~06, TRC-08 | 저장소·PR·diff·코멘트 | /api/projects/{id}/github/** | 미정 |
| PLN-10~12 | API·ERD·화면 설계 초안 생성 | POST /api/projects/{id}/design-generations | 미정 |
| DOC-14 | 추가 자료 첨부 | POST /api/projects/{id}/attachments | 미정 |
| MTG-07 | 회의 음성 입력 | POST /api/projects/{id}/meetings/{meeting_id}/audio | 미정 |
| AGT-08·09 | 다중 에이전트·추가 클라이언트 | 기존 token/session·MCP 계약 확장 | 미정 |
| 확장 CTX | 고급 패킷·공유 기록 | 기본 MCP 계약 확장 | 미정 |
| NTF-07·08 | 실시간 전파·로컬 제어 | 별도 전송·실행 제어 계약 | 미정 |
| PRJ-04 시작 가이드 상태 | 가이드를 봤음·숨김·'기준 문서 읽었어요'를 기기 사이에 맞춤. 지금은 FE가 계정·프로젝트별로 브라우저에 저장 | GET·PATCH /api/projects/{id}/me/guide-state | 전진 |
| MEM-02 내 대기 초대 목록 | 초대받고 나중에 가입한 사람에게 받은 초대를 모아 보여 줌(캔버스 메모의 대기열). 지금 화면은 초대 링크 토큰을 회원가입 뒤까지 들고 가 MEM-A05로 수락 | GET /api/me/invitations | 전진 |

## 9. 검토 항목

| ID | 계약 항목 | 담당 | 확인할 내용 |
| --- | --- | --- | --- |
| Q01 | 항목 본문 스키마 | 오서현·박상은 | kind별 content 필수 키·schema_version, API/DATA 계약 구조. 107의 비교 예시는 최종 본문 스키마를 선점하지 않음 |
| Q02 | 작업·검증·관계 | 변준성 주관 / 양지훈·전진 | D2 PASS 판정표, 107의 필수 검사 목록·COMPLETE/PARTIAL·정보 부족 매핑(합의 전), 관계 강도, unlinked 형식, 무효화 의존 깊이, 팀장 인계·대리 범위 |
| Q03 | 발행·완료·복구 | 오서현·변준성 / 전진 | D7 같은 트랜잭션, 커밋 후 이벤트 유실 방지, 완료 delta(6절·TSK-A07에 반영, 팀 확인) |
| Q04 | 계정·세션 | 전진 | 비밀번호 정책(수정안 기본안: 영문·숫자·특수문자 포함 8~128자), 세션 쿠키·만료·CSRF, 로그인 유지 기간, 가입 인증 코드의 다시 받기 간격·틀린 입력·발송 횟수, 임시 비밀번호 길이·유효 기간, 발급 때 기존 비밀번호·세션 처리, 변경 전 다른 API 차단 여부 |
| Q05 | 초대·탈퇴·제외 | 전진 / 변준성 | 이메일 지정 초대 제한, 토큰 만료·1회 사용, 열린 작업 인계·익명화. 초대 메일 문구·발신 주소·발송 한도, 보내자마자 자동 참여시킬지(캔버스 메모 note-ax681p와 설정 › 팀원 초대 문구 '같은 이메일로 로그인하면 바로 이 프로젝트로 들어와요'는 자동 참여, 초대 수락 화면과 초대 보낸 뒤 문구는 수락 필요), 탈퇴 때 혼자 쓰는 프로젝트를 지울지 남길지, 팀원으로만 참여한 프로젝트에서 자동으로 빠질지(회원 탈퇴 화면 기본안: 팀원 참여 프로젝트는 자동으로 빠지고 혼자 쓰는 프로젝트는 함께 삭제, 남긴 기록은 보존) |
| Q06 | 토큰·연결 관측 | 전진 / 변준성 | 1인 1에이전트의 프로젝트별 활성 토큰 수, 보고 지연 임계값 |
| Q07 | 문서 파일·분석 | 오서현·박상은 | 형식·용량·저장소·같은 kind 복수, 파싱/OCR·분할, 분석/초안 job 상태·실패·재시도·사람 수정 보존 |
| Q08 | 초안·제안 충돌 | 오서현 | 초안 해시·조회 범위·해시 발급, 초안 편집·삭제, expected_updated_at 정밀도 |
| Q09 | 회의 입력·기록 | 오서현·양지훈 | 참여자 분석 권한·entry kind, MTG-08 전사와 입력 경로, 회의록·초안 연결 |
| Q10 | 컨텍스트·MCP | 전진·변준성·박상은 | MCP-05~07 최소 지원 범위, PRIVATE·리비전·패킷 검색, 개인 맥락 정책 |
| Q11 | AI 실행·검색 | 양지훈·박상은 / 변준성 | 공통 오류·usage, 107의 execution_budget_ms·초기 명시적 재시도 정책·진단 보존 방식(합의 전), lease, 모델·임베딩 차원, 출력 검증·입력 크기. 검증 LLM과 임베딩의 운영값을 자동 공유하지 않음 |
| Q12 | 공통 FE·BE 계약 | 전진·변준성·오서현·박민용 | 문자열 ID·페이지·신규 code·멱등 보존, 정적 summary 경로, 조회/집계 구현 |
| Q13 | 비비 서버 연결 | 오서현·변준성·박상은 | 문서 기반 질문·수정 생성과 작업 배정 연결, 영구 대화 저장 여부(수정안 기본안: 창을 연 동안만), '근거가 틀려요' 피드백 활용, 자주 묻는 질문 칩의 출처 |
| Q14 | 문서 승인 경로 | 오서현 / 팀 전체 | 수정안은 공용 문서함 안에서 비비 검토 → 팀장 승인을 끝낸다. 다만 변경 제안 보드끼리 어긋난다: 머리의 '공용 문서함에서 제안하기', 빈 화면의 '팀원이 공용 문서함에서 문서를 추가·수정·삭제하자고 제안하면 여기에 모여요', 반려·기준 바뀜의 '공용 문서함에서 다시 올리기·고치기'는 추천안 쪽이고, 새 변경 제안·반려 확인 화면은 '설계 항목만, 문서는 공용 문서함에서'다. 기본안은 문서 버전 하위 API(DOC-A13~A23)다. 변경 제안(DocumentChange)에 얹을지, 추천안(문서함은 승인본 읽기, 새 버전은 변경 제안)으로 갈지 정한다 |
| Q15 | 최초 기준 설계 항목 | 오서현·박상은 | 기획 분석 화면이 빠져 PLN-A10의 항목 버전·초안 해시를 검토할 화면이 없다. 기본안: 필수 4종이 모두 승인되면 승인 문서에서 자동 추출한 항목을 그대로 넣어 기준 v1을 발행하고, 이후 변경 제안으로 고친다. 승인 이력 화면도 기준 v1을 '공용 문서함에서 문서 4종 승인 · 문서 4 · 설계 항목 2'로 보인다 |
| Q16 | 기준 이후 문서 반영 | 오서현·변준성·양지훈 | 기준 v1 뒤 문서 승인(DOC-A21)과 회의 끝내기(MTG-A13)가 기준 버전을 올리는지, 관련 검증을 STALE로·진행 중 작업을 WAITING(REVALIDATION)으로 바꾸는지, 영향 분석을 돌리는지. 기본안: 변경 제안 승인(CHG-A09)과 같은 규칙. 문서함 화면은 승인되면 '알림 API 명세서 v1이 팀의 기준 버전이 됐어요'라고 보여 기준이 바뀌는 쪽이다. 잘못 반영된 회의 결과는 새 문서 버전으로 되돌린다 |
| Q17 | 회의 자동 개설 | 오서현·양지훈·변준성 | 비비가 회의를 여는 기준(기본안: 담당자가 다른 활성 작업 사이의 BLOCKING CONFLICT), 중복 방지 범위, AI 후보만으로 사람을 부르지 않는다는 AI-A03 원칙과의 관계, 변경 제안의 '회의로 논의' 버튼을 남길지 |
| Q18 | 접속·위치 공개 | 전진·박민용 | 사람의 위치를 '사무실 안 자리'까지만 보일지 열어 둔 메뉴까지 보일지, 보고 주기·TTL, 폴링·SSE·WebSocket 중 전송 수단(NTF-07 확장 후보와 함께) |
| Q19 | 비비 문서 검토 운영 | 박상은·오서현·양지훈 | 비교 대상(승인 문서, 기준이 없으면 함께 올린 초안), 단계 이름과 목표 소요 시간, 실패 때 재시도 한도와 팀장 우회 여부(기본안: 우회 없음), 업로드마다 드는 AI 비용 상한 |
| Q20 | 같은 종류 문서 여러 개 | 오서현·박상은 | DOC-A01 확정 정책은 종류마다 문서 1개다. 수정안 예시('알림 API 명세서'를 두 번째 API 명세서로 등록)는 복수 등록이 필요하다. 허용하면 필수 4종 충족은 '그 종류에 승인 문서가 하나 이상'으로 판정한다 |
| Q21 | 대시보드 비용·집계 | 변준성·전진 | 비용을 팀장만 볼지(지금 MEMBER), 에이전트를 토큰별로 셀지 팀원별로 묶을지, 프로젝트 전체 관계 그래프가 필요한지 |
| Q22 | 화면 상태 저장 | 전진·변준성·박민용 | 가이드 표시 상태는 브라우저 저장으로 정함(서버 저장은 8절 확장 후보). 시작 가이드 종료 조건(팀장 = 발행+초대, 팀원 = 에이전트 연결+첫 계획), 팀장을 넘겨받은 사람에게 가이드를 다시 띄울지, 기준 발행 전 팀원이 할 수 있는 일, 도시 부지 번호를 멤버십별로 서버에 둘지와 부지 상한 |
| Q23 | 변경 제안 검토 시작 | 오서현 | 승인 전에 IN_REVIEW(CHG-A08)를 꼭 거칠지, 팀장이 제안을 열면 자동으로 할지(기본안: 자동) |

Q07 중 업로드는 2026-10-27 [DOC-A01 정책](01-backend-rest.md#doc-a01)으로 확정했다. 문서 API의 사용자 식별은 공통 로그인·CallerContext 계약(PR #22)을 따른다. 문서 전용 세션 속성·CSRF 토큰 발급 API·X-CSRF-Token 헤더는 사용하지 않는다. 문서 변경 요청은 Origin(없을 때 Referer)의 scheme·host·유효 port를 beez.cors.allowed-origins와 정확히 대조하며, 출처 누락·불일치·잘못된 형식은 403 request_origin_rejected로 거부한다. 이 검사는 문서 API 범위이며 전역 인증 정책을 변경하지 않는다.

## 10. 공통 스키마

필드 타입의 링크는 중첩 객체의 상세 정의다. 예제는 상세 API에 제시한다. 선택 필드와 조건부 필수의 설명을 함께 적용한다. ID·Time·object는 3절 형식이다.

<a id="schema-account"></a>

### Account

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계정 ID |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값 또는 URL |
| `created_at` | `Time` | Y | 가입 시각 |

<a id="schema-baselinesummary"></a>

### BaselineSummary

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기준 설계 ID |
| `version_no` | `integer` | Y | 프로젝트 내 기준 설계 번호 |
| `created_at` | `Time` | Y | 발행 시각 |

<a id="schema-project"></a>

### Project

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `name` | `string` | Y | 프로젝트 이름 |
| `description` | `string` | N | 프로젝트 설명 |
| `my_role` | `LEADER\|MEMBER` | Y | 조회자의 프로젝트 역할 |
| `design` | `glass\|brick\|modern\|tower\|wood` | Y | 건물 디자인. 팀원 모두의 도시·워크스페이스에 같이 보임. 수정안 요청 |
| `phase` | `PREPARING\|STARTED\|BASELINED` | Y | 준비 중(필수 4종 미등록) · 시작됨(4종 등록, 기준 전) · 기준 있음. 수정안 요청 |
| `member_count` | `integer` | Y | 활성 멤버 수. 수정안 요청 |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 현재 기준 설계. 최초 발행 전 생략 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 시각 |

<a id="schema-member"></a>

### Member

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 멤버 ID |
| `account_id` | `ID` | Y | 계정 ID |
| `name` | `string` | Y | 표시 이름 |
| `role` | `LEADER\|MEMBER` | Y | 프로젝트 역할 |
| `domain` | `string` | N | 담당 영역 |
| `role_label` | `string` | N | 프로젝트 내 역할 라벨 |
| `color` | `string` | N | 표시 색상 |
| `agent_connected` | `boolean` | Y | 에이전트 연결 관측 여부 |
| `agent_status` | `CONNECTED\|CHECK_REQUIRED\|NOT_CONNECTED` | Y | 팀 목록·HUD의 '연결됨·연결 미확인·연결 전'. CHECK_REQUIRED는 AgentConnection.status_check_required. 수정안 요청 |

<a id="schema-invitation"></a>

### Invitation

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 초대 ID |
| `project_id` | `ID` | Y | 대상 프로젝트 |
| `invited_email` | `string` | N | 주소를 지정한 초대의 대상 |
| `expires_at` | `Time` | Y | 만료 시각 |
| `accepted_at` | `Time` | N | 수락 시각 |
| `revoked_at` | `Time` | N | 폐기 시각 |

<a id="schema-agenttoken"></a>

### AgentToken

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 토큰 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `label` | `string` | N | 토큰 이름 |
| `created_at` | `Time` | Y | 발급 시각 |
| `expires_at` | `Time` | N | 만료 시각. 화면 예시는 발급 3개월 뒤. 만료 정책(Q06)이 없으면 생략. 수정안 요청 |
| `scopes` | `string[]` | Y | 쓸 수 있는 범위. 발급 화면은 '작업 계획·기록·설계 조회'처럼 사람이 읽는 말로 보임. 값 목록은 Q06. 수정안 요청 |
| `revoked_at` | `Time` | N | 폐기 시각 |
| `last_used_at` | `Time` | N | 마지막 사용 |

<a id="schema-source"></a>

### Source

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 근거 문서 |
| `document_version_id` | `ID` | Y | 정확한 문서 버전 |
| `version_no` | `integer` | Y | 문서 버전 번호 |
| `location` | `string` | Y | 원문 항목 위치 |

<a id="schema-validationtextlocator"></a>

### ValidationTextLocator

107의 검증 원문 입력 전용이다. PR #18의 `SourceLocator` 행 규칙을 재사용하며 기존 Source의 표시용 location을 대체하지 않는다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `text_lines` | Y | 정규화된 텍스트의 행 범위 |
| `start_line` | `integer` | Y | 1부터 시작하는 시작 행 |
| `end_line` | `integer` | Y | 마지막 행 포함. start_line 이상 |

<a id="schema-validationevidence"></a>

### ValidationEvidence

backend가 권한·원문 버전·범위를 확인하고 복사한 불변 근거다. 기존 [RAG 개발 계획](../superpowers/plans/2026-10-26-document-rag-development.md)의 RetrievedEvidence를 검증 요청으로 연결한다. AI가 저장소·벡터 DB를 다시 조회하지 않는다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `evidence_id` | `string` | Y | 요청 안에서 유일한 근거 키 |
| `design_item_version_id` | `ID` | Y | approved_items 안의 승인 항목 버전 |
| `source` | [Source](00-common.md#schema-source) | Y | 정확한 문서 버전과 표시 위치 |
| `locator` | [ValidationTextLocator](00-common.md#schema-validationtextlocator) | Y | 원문을 복원할 범위 |
| `text` | `string` | Y | locator의 원문 발췌. 각 행의 LF를 포함한 정확한 문자열 |
| `text_hash` | `string` | Y | 전체 정규화 원문의 SHA-256, 소문자 16진수 64자리. 발췌 자체의 해시가 아님 |
| `normalization_version` | `utf8-lf-v1` | Y | 선두 BOM 제거, CRLF/CR → LF. trim·NFKC 금지 |

backend는 승인 항목과 원문 출처의 연결 및 조회 허용 범위를 검사한다. 원문 bytes의 `content_hash`와 정규화 원문의 `text_hash`는 서로 다른 값일 수 있다. 원문 또는 항목이 승인됐다는 이유로 연결된 다른 데이터까지 자동 승인된 것으로 간주하지 않는다. 사람이 편집한 승인 content와 원문이 다르면 구조화 계약 비교의 기준은 승인 content이며 원문과의 충돌도 명시한다. 수동 입력·기존 승인 항목을 사용할 수 있으므로 자동 설계 항목 추출은 선행 조건이 아니다. 원문 RAG만 준비되고 비교할 승인 항목이 없는 경우에는 항목 대조를 수행한 것으로 간주하지 않는다.

<a id="schema-validationcheck"></a>

### ValidationCheck

backend가 계획·승인 참조·관련 작업과 검증 규칙으로 정한 의미 검사 단위다. 사용자나 LLM이 검사 범위를 줄이지 못한다. 키는 요청 안에서 유일하며 ID는 요청의 객체 집합 안에서만 참조한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `key` | `string` | Y | 요청 안에서 유일한 검사 키 |
| `kind` | `PLAN_COMPLETENESS\|DESIGN_ALIGNMENT\|RELATED_PLAN_CONFLICT` | Y | 계획 정보·승인 설계 정합성·관련 계획 충돌 검사 |
| `design_item_version_ids` | `ID[]` | Y | 이 검사에서 읽을 approved_items의 버전 ID. 해당 없으면 [] |
| `related_plan_revision_ids` | `ID[]` | Y | 이 검사에서 읽을 related_plans의 리비전 ID. 해당 없으면 [] |

DESIGN_ALIGNMENT에는 하나 이상의 설계 버전, RELATED_PLAN_CONFLICT에는 하나 이상의 다른 작업 리비전이 필요하다. `checks=[]`인 요청은 보내지 않으며, 의미 분석이 불필요하면 backend가 규칙 판정으로 종료한다. 구체적인 필수 검사 선정은 Q02에서 합의하고 실행 스냅샷에 고정한다.

<a id="schema-unassessedvalidationcheck"></a>

### UnassessedValidationCheck

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `check_key` | `string` | Y | 요청 checks에 존재하는 미검사 키 |
| `reason` | `EVIDENCE_INSUFFICIENT\|UNSUPPORTED_CONTENT` | Y | 의미 분석 후 확인된 근거 부족·미지원 내용 |
| `message` | `string` | Y | 검사하지 못한 내용과 필요한 추가 근거·조치 |

이 reason은 검증 분석 결과의 진단이며 `validation_finding.kind`나 문서·색인 상태 enum에 추가하지 않는다. 알려진 입력 형식·참조·지원 버전 오류는 호출 전 422로 거부하고, 모델 실패·timeout·잘못된 출력을 이 진단으로 숨기지 않는다.

<a id="schema-documentversion"></a>

### DocumentVersion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 문서 버전 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `version_no` | `integer` | Y | 문서 내 버전 번호 |
| `status` | `DRAFT\|APPROVED\|SUPERSEDED\|REJECTED` | Y | 문서 버전 상태 |
| `review_stage` | `BEE_REVIEWING\|SUGGESTIONS_PENDING\|READY_TO_REQUEST\|AWAITING_APPROVAL\|REJECTED\|APPROVED` | N | 공용 문서함 검토 단계. DRAFT·REJECTED·APPROVED 버전에 표시. 수정안 요청 |
| `file_name` | `string` | Y | document-{id}-v{version_no}.{md\|txt} 생성 다운로드명 |
| `media_type` | `string` | Y | MIME |
| `content_hash` | `string` | Y | 원본 해시 |
| `content` | `string` | N | 텍스트 본문. 비비 제안을 반영하면 반영본. 바이너리는 생략 |
| `original_content_hash` | `string` | N | 올린 원본의 정규화 해시. 반영본과 다를 때만. 수정안 요청 |
| `created_by` | `ID` | Y | 올린 계정 |
| `requested_by` | `ID` | N | 팀장 승인을 요청한 계정. 수정안 요청 |
| `requested_at` | `Time` | N | 승인 요청 시각. 수정안 요청 |
| `last_rejection` | [DocumentRejection](00-common.md#schema-documentrejection) | N | 마지막 반려. 수정안 요청 |
| `source_meeting_id` | `ID` | N | 회의 끝내기(MTG-A13)로 만든 버전이면 그 회의. 수정안 요청 |
| `is_current_baseline` | `boolean` | Y | 현재 기준에 들어 있는 버전. 수정안 요청 |
| `created_at` | `Time` | Y | 생성 시각 |

<a id="schema-document"></a>

### Document

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 문서 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 문서 유형 |
| `title` | `string` | Y | 문서 제목 |
| `domain` | `string` | N | 분류 |
| `approved_version` | [DocumentVersion](00-common.md#schema-documentversion) | N | 현재 기준의 승인본 |
| `latest_draft` | [DocumentVersion](00-common.md#schema-documentversion) | N | 최신 미승인 초안 |
| `preview_text` | `string` | N | 본문 앞부분 300자. 썸네일 보기 미리보기. 수정안 요청 |

<a id="schema-designitemversion"></a>

### DesignItemVersion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 항목 버전 ID |
| `design_item_id` | `ID` | Y | 항목 ID |
| `key` | `string` | Y | 서버 발급 프로젝트 내 표시 키 |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 항목 종류 |
| `version_no` | `integer` | Y | 항목 버전 번호 |
| `status` | `DRAFT\|APPROVED\|SUPERSEDED\|REJECTED` | Y | 항목 버전 상태 |
| `schema_version` | `integer` | Y | 본문 스키마 버전 |
| `content` | `object` | Y | kind별 본문 구조. Q01 검토 대상 |
| `source` | [Source](00-common.md#schema-source) | N | 원문 출처 |
| `content_hash` | `string` | N | DRAFT 본문 경합 검사 해시 |

<a id="schema-documentref"></a>

### DocumentRef

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 문서 ID |
| `document_version_id` | `ID` | Y | 문서 버전 ID |

<a id="schema-versionref"></a>

### VersionRef

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_item_id` | `ID` | Y | 항목 ID |
| `design_item_version_id` | `ID` | Y | 항목 버전 ID |

<a id="schema-designref"></a>

### DesignRef

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_item_id` | `ID` | Y | 항목 ID |
| `design_item_version_id` | `ID` | Y | 항목 버전 ID |
| `relation` | `READS\|IMPLEMENTS\|CHANGES` | Y | 계획 관계 |
| `proposed_content` | `object` | N | CHANGES에서 필수. 나머지 관계는 생략 |

<a id="schema-validationdesignref"></a>

### ValidationDesignRef

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_item_id` | `ID` | Y | 항목 ID |
| `design_item_version_id` | `ID` | Y | 항목 버전 ID |
| `relation` | `READS\|IMPLEMENTS\|CHANGES\|DEPENDENCY` | Y | 검증 참조 관계 |

<a id="schema-baseline"></a>

### Baseline

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기준 설계 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `version_no` | `integer` | Y | 프로젝트 내 기준 번호 |
| `documents` | [DocumentRef[]](00-common.md#schema-documentref) | Y | 정확한 발행 문서 버전 |
| `items` | [VersionRef[]](00-common.md#schema-versionref) | Y | 정확한 발행 항목 버전 |
| `approved_by` | `ID` | Y | 승인한 사람 계정 |
| `created_at` | `Time` | Y | 발행 시각 |

<a id="schema-planningquestion"></a>

### PlanningQuestion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 질문 ID |
| `question` | `string` | Y | 보완 질문 |
| `source` | [Source](00-common.md#schema-source) | N | 출처 |
| `answer` | `string` | N | 답변 |
| `answered_by` | `ID` | N | 답변 계정 |
| `answered_at` | `Time` | N | 답변 시각 |

<a id="schema-usage"></a>

### Usage

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `request_key` | `string` | Y | 모델 호출 중복 식별값 |
| `feature` | `DOCUMENT_PARSE\|TASK_VALIDATION\|CHANGE_IMPACT\|EMBEDDING\|BEE_CHAT\|BEE_DRAFT\|TASK_ASSIGNMENT\|MEETING_ANALYSIS` | Y | 호출 기능 |
| `model` | `string` | Y | 사용 모델 |
| `input_tokens` | `integer` | N | 입력 토큰 수 |
| `output_tokens` | `integer` | N | 출력 토큰 수 |
| `latency_ms` | `integer` | Y | 호출 지연 ms |
| `failed` | `boolean` | Y | 실패 여부 |
| `estimated_cost_amount` | `string` | N | 추정 비용. 단가 없으면 생략 |
| `currency` | `string` | N | 통화 |
| `tokens_known` | `boolean` | Y | 공급자 토큰 수를 확인함. false이면 모르는 값을 0으로 만들지 않음 |

`llm_usage.request_key`는 실제 모델 호출 한 건을 식별하는 유일 키다. 검증 호출은 `llm-validate-{validation_id}-attempt-{n}-call-{k}`로 만들어 동일 실행 안에서도 실제 호출마다 k를 증가시킨다. 같은 실제 호출 기록을 중복 전달할 때만 같은 키를 재사용한다. 입력 요청의 request_key를 여러 실제 호출의 usage key로 그대로 재사용하지 않는다.

<a id="schema-generation"></a>

### Generation

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 호출 시 필수 모델 |
| `text` | `string` | Y | 응답 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `unresolved` | `string[]` | Y | 미확정 사항 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

<a id="schema-designdependency"></a>

### DesignDependency

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from_design_item_id` | `ID` | Y | 의존하는 항목 |
| `to_design_item_id` | `ID` | Y | 의존 대상 |
| `origin` | `EXPLICIT\|AI_CANDIDATE` | Y | 명시 관계 또는 AI 후보. AI 후보는 무효화 근거가 아님 |

<a id="schema-changeitem"></a>

### ChangeItem

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `operation` | `CREATE\|UPDATE\|RETIRE` | Y | 변경 종류 |
| `design_item_id` | `ID` | N | UPDATE/RETIRE 필수 항목 |
| `base_design_item_version_id` | `ID` | N | UPDATE/RETIRE 필수 기준 버전 |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | N | CREATE 필수 종류 |
| `schema_version` | `integer` | N | CREATE/UPDATE에서 proposed_content를 보내면 필수 구조 버전 |
| `proposed_content` | `object` | N | CREATE/UPDATE 본문. proposed_text만 오면 서버가 채움(Q01) |
| `proposed_text` | `string` | N | 사람이 쓴 문장 하나. CREATE/UPDATE에서 proposed_content와 둘 중 하나 필수. 수정안 요청 |

<a id="schema-documentchange"></a>

### DocumentChange

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 문서 |
| `base_document_version_id` | `ID` | Y | 기준 원문 버전 |
| `after_content` | `string` | N | 텍스트 수정안. proposed_document_version_id와 둘 중 하나 |
| `proposed_document_version_id` | `ID` | N | 업로드한 DRAFT 버전. after_content와 배타적 |

<a id="schema-changeproposalinput"></a>

### ChangeProposalInput

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `meeting_id` | `ID` | N | 관련 회의. 수정안 요청 |
| `previous_proposal_id` | `ID` | N | 반려된 제안을 고쳐 새로 올릴 때 이전 제안. 수정안 요청 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |

<a id="schema-changeproposal"></a>

### ChangeProposal

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `meeting_id` | `ID` | N | 관련 회의. 수정안 요청 |
| `previous_proposal_id` | `ID` | N | 이 제안이 고쳐 올린 이전 제안. 수정안 요청 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED\|WITHDRAWN` | Y | 제안 상태. WITHDRAWN은 수정안 요청(CHG-A15) |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `agent` | `object` | N | AGENT 제출이면 {agent_session_id, name(토큰 label), member_id}. 수정안 요청 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |

<a id="schema-approval"></a>

### Approval

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 승인 이력 |
| `proposal_id` | `ID` | Y | 제안 |
| `approved_by` | `ID` | Y | 승인 계정 |
| `approved_at` | `Time` | Y | 승인 시각 |
| `baseline_id` | `ID` | Y | 발행 기준 |

<a id="schema-changecomment"></a>

### ChangeComment

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 의견 ID |
| `proposal_id` | `ID` | Y | 제안 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `kind` | `COMMENT\|REVIEW_REQUEST` | Y | 의견 종류 |
| `body` | `string` | Y | 의견 본문 |
| `created_at` | `Time` | Y | 작성 시각 |

<a id="schema-explicitimpact"></a>

### ExplicitImpact

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 연결 작업 |
| `member_id` | `ID` | Y | 담당 멤버 |
| `design_item_id` | `ID` | Y | 변경 항목 |
| `validation_id` | `ID` | N | 영향 검증 |

<a id="schema-impactcandidate"></a>

### ImpactCandidate

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | N | 후보 작업 |
| `member_id` | `ID` | N | 후보 담당자 |
| `design_item_id` | `ID` | N | 후보 항목 |
| `reason` | `string` | Y | 후보 근거 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |

<a id="schema-changeimpact"></a>

### ChangeImpact

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `explicit` | [ExplicitImpact[]](00-common.md#schema-explicitimpact) | Y | 명시 관계 영향 |
| `candidates` | [ImpactCandidate[]](00-common.md#schema-impactcandidate) | Y | AI 영향 후보 |
| `analysis_status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | AI 분석 상태 |
| `generation` | [Generation](00-common.md#schema-generation) | N | 분석 설명 |
| `error_code` | `string` | N | 실패 원인 |

<a id="schema-taskplaninput"></a>

### TaskPlanInput

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `title` | `string` | Y | 작업 제목 |
| `goal` | `string` | Y | 작업 목표 |
| `included_scope` | `string[]` | Y | 포함 범위 |
| `excluded_scope` | `string[]` | Y | 제외 범위 |
| `approach` | `string` | Y | 접근 방식 |
| `completion_criteria` | `string[]` | Y | 완료 기준 |
| `dependency_task_ids` | `ID[]` | Y | 선행 작업 |
| `design_refs` | [DesignRef[]](00-common.md#schema-designref) | Y | 확정 참조 |
| `unlinked` | `object[]` | Y | 미연결 내용. 세부 항목 Q02. 수정안 요청: 화면은 줄마다 문장 하나를 입력하므로 `{text: string}` 배열로 확정 |
| `no_contract_change` | `boolean` | Y | 계약 변경 없음의 명시적 선언 |
| `context_packet_id` | `ID` | N | 근거 패킷. 없으면 근거 패킷 없음 표시 |

<a id="schema-taskplanrevision"></a>

### TaskPlanRevision

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계획 리비전 ID |
| `task_id` | `ID` | Y | 작업 |
| `revision_no` | `integer` | Y | 작업 내 리비전 번호 |
| `plan` | [TaskPlanInput](00-common.md#schema-taskplaninput) | Y | 불변 계획 |
| `created_at` | `Time` | Y | 생성 시각 |
| `created_by` | `ID` | Y | 작성 계정. 에이전트 작성이면 토큰 소유 계정 |
| `agent_token_id` | `ID` | N | 에이전트로 작성한 경우의 토큰 |

<a id="schema-relatedtaskref"></a>

### RelatedTaskRef

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 관련 작업 |
| `plan_revision_id` | `ID` | Y | 대조한 계획 리비전 |

<a id="schema-finding"></a>

### Finding

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 발견 항목 |
| `source` | `RULE\|MODEL` | Y | 규칙·분석 경로 구분. BE가 만드는 kind=ERROR finding도 MODEL |
| `kind` | `MISMATCH\|MISSING_INPUT\|MISSING_POLICY\|CONFLICT\|STALE_REF\|ERROR` | Y | 분류 |
| `severity` | `INFO\|WARNING\|BLOCKING` | Y | 중요도. BLOCKING이면 PASS 불가 |
| `message` | `string` | Y | 설명 |
| `design_item_id` | `ID` | N | 관련 항목 |
| `design_item_version_id` | `ID` | N | 근거 항목 버전. design_item_id가 있으면 필수 |
| `related_task_id` | `ID` | N | 관련 작업 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `resolution` | `FIX_PLAN\|PROPOSE_CHANGE\|HUMAN_REVIEW\|RETRY` | Y | 후속 경로 |
| `next_action` | `string` | N | 후속 경로 설명. severity=BLOCKING이면 필수: 작업 현황 화면이 막힌 항목마다 고칠 방법을 한 줄로 보여 줌(수정안 요청) |
| `validation_run_id` | `ID` | N | 이 finding을 낸 실행. MODEL finding이면 필수이며, kind=ERROR는 현재 실패 attempt의 run을 참조 |

kind=ERROR finding은 source=MODEL·severity=BLOCKING이며 해당 validation과 현재 실패 attempt에 속한 run을 반드시 참조한다. BE가 생성하는 실행 오류이며 실제 모델 호출 여부와 구분한다. 모든 ERROR 전이·복구·재시도 규칙은 [검증 실행 오류 규약](#validation-execution-errors)을 따른다.

<a id="schema-validation"></a>

### Validation

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `trigger` | `AGENT\|REVALIDATION\|DELTA` | Y | 검증 계기 |
| `stale_reason` | `string` | N | STALE일 때 필수. 무효화 원인 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 설계 대조 재사용 |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](#validation-execution-errors) |

<a id="schema-taskdetail"></a>

### TaskDetail

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `delta_validation_id` | `ID` | N | 완료 delta 재검증. WAITING(REVALIDATION) 중 포함 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

<a id="schema-completioncheck"></a>

### CompletionCheck

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `criterion` | `string` | Y | 완료 기준 |
| `met` | `boolean` | Y | 충족 여부 |

<a id="schema-taskstateinput"></a>

### TaskStateInput

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `event` | `START\|RESUME\|WAIT\|COMPLETED\|CANCEL` | Y | 전이 이벤트 |
| `plan_revision_id` | `ID` | Y | 현재 계획 |
| `expected_lock_version` | `integer` | Y | 조회한 lock_version |
| `validation_id` | `ID` | N | START/RESUME 필수 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY` | N | WAIT이면 필수. WAIT는 IN_PROGRESS에서만 가능. REVALIDATION은 시스템 전용이라 지정하면 400 invalid_transition |
| `summary` | `string` | N | 진행/완료 요약 |
| `delta` | `object[]` | N | 완료 시 계획 대비 변경. 있으면 WAITING(REVALIDATION)으로 두고 delta 검증이 PASS하면 서버가 COMPLETED로 전이 |
| `completion_checks` | [CompletionCheck[]](00-common.md#schema-completioncheck) | N | 완료 기준 확인 |

<a id="schema-relatedtask"></a>

### RelatedTask

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 관련 작업 |
| `plan_revision_id` | `ID` | Y | 대조한 계획 리비전 |
| `status` | `READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 관련 작업 상태 |

<a id="schema-contextpacket"></a>

### ContextPacket

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 패킷 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `task_id` | `ID` | N | 연결 작업 |
| `baseline_id` | `ID` | Y | 조회 당시 기준 |
| `request` | `string` | Y | 개발 요청 |
| `design_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 승인 설계 |
| `constraints` | `string[]` | Y | 제약 |
| `unresolved` | `string[]` | Y | 미확정 항목 |
| `related_tasks` | [RelatedTask[]](00-common.md#schema-relatedtask) | Y | 관련 작업 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `created_at` | `Time` | Y | 생성 시각 |
| `content_hash` | `string` | Y | 패킷 해시 |

<a id="schema-contextrecordinput"></a>

### ContextRecordInput

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `task_id` | `ID` | N | 연결 작업 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 기록 종류 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `summary` | `string` | Y | 검색용 공유 요약 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

<a id="schema-contextaccesslog"></a>

### ContextAccessLog

누가 어떤 설계 버전·패킷·기록을 봤는지 (ERD context_access_log, CTX-05·06). 원문·토큰 값은 없다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 이력 ID |
| `account_id` | `ID` | Y | 호출 계정. 에이전트면 토큰 소유 계정 |
| `agent_token_id` | `ID` | N | 에이전트 호출이면 토큰. 사람 세션은 생략 |
| `task_id` | `ID` | N | 관련 작업 |
| `tool` | `string` | Y | 도구·API 이름. 예: get_design_changes, get_development_context, CTX-A01 |
| `target_kind` | `BASELINE\|PACKET\|CONTEXT_RECORD` | Y | 본 대상의 종류 |
| `target_id` | `ID` | N | 본 대상 |
| `baseline_id` | `ID` | N | 그때 참조한 기준 설계 |
| `packet_id` | `ID` | N | 봤거나 만든 패킷 |
| `accessed_at` | `Time` | Y | 조회 시각 |

<a id="schema-contextrecordsummary"></a>

### ContextRecordSummary

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 리비전 ID |
| `root_id` | `ID` | Y | 최초 리비전 ID |
| `revision_no` | `integer` | Y | 리비전 번호 |
| `task_id` | `ID` | N | 관련 작업 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 종류 |
| `summary` | `string` | Y | 공유 요약 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `is_latest_revision` | `boolean` | Y | 최신 기록 리비전 |
| `references_current` | `boolean` | Y | 참조 설계의 현재성 |
| `created_at` | `Time` | Y | 작성 시각 |

<a id="schema-contextrecord"></a>

### ContextRecord

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 리비전 ID |
| `root_id` | `ID` | Y | 최초 리비전 ID |
| `revision_no` | `integer` | Y | 리비전 번호 |
| `task_id` | `ID` | N | 관련 작업 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 종류 |
| `summary` | `string` | Y | 공유 요약 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `is_latest_revision` | `boolean` | Y | 최신 기록 리비전 |
| `references_current` | `boolean` | Y | 참조 설계의 현재성 |
| `created_at` | `Time` | Y | 작성 시각 |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

<a id="schema-agentconnection"></a>

### AgentConnection

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 에이전트 세션 ID |
| `member_id` | `ID` | Y | 소유 멤버 |
| `connected` | `boolean` | Y | 최근 연결 관측 |
| `last_report_at` | `Time` | N | 마지막 통신 시각 |
| `status_check_required` | `boolean` | Y | 보고 지연으로 확인 필요 |
| `last_error_code` | `token_revoked\|token_expired\|membership_ended\|no_report` | N | 마지막 연결 실패 원인. 연결 확인 화면이 이유와 할 일을 보여 줌. 수정안 요청 |

<a id="schema-agentwork"></a>

### AgentWork

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `member_id` | `ID` | Y | 멤버 ID |
| `agent_session_id` | `ID` | N | 연결 세션 |
| `agent_name` | `string` | N | 토큰 label. 수정안 요청 |
| `current_task_id` | `ID` | N | 명시 보고된 작업 |
| `task_title` | `string` | N | 보고된 작업 제목. 수정안 요청 |
| `task_status` | `READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | N | 보고된 작업 상태 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | N | 작업 검증 상태 |
| `waiting_reason` | `string` | N | 대기 사유 |
| `last_summary` | `string` | N | MCP-12 `summary` 원문. AI로 다시 요약하지 않음. 수정안 요청 |
| `reported_at` | `Time` | N | 마지막 작업 보고 시각. 수정안 요청 |

<a id="schema-requireddocument"></a>

### RequiredDocument

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 필수 종류 |
| `uploaded` | `boolean` | Y | 업로드됨 |
| `approved` | `boolean` | Y | 승인됨 |
| `document_id` | `ID` | N | 올린 문서. 수정안 요청 |
| `latest_version_id` | `ID` | N | 검토 중이거나 마지막으로 올린 버전. 수정안 요청 |
| `review_stage` | `BEE_REVIEWING\|SUGGESTIONS_PENDING\|READY_TO_REQUEST\|AWAITING_APPROVAL\|REJECTED\|APPROVED` | N | 그 버전의 검토 단계. 수정안 요청 |

<a id="schema-documentanalysis"></a>

### DocumentAnalysis

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 분석 원본 버전 |
| `status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | 분석 상태 |
| `items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 추출 항목 초안 |
| `questions` | [PlanningQuestion[]](00-common.md#schema-planningquestion) | Y | 보완 질문 |
| `error_code` | `string` | N | 실패 원인 |

<a id="schema-searchhit"></a>

### SearchHit

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `DOCUMENT\|DOCUMENT_CHUNK\|DESIGN_ITEM` | Y | 원문 키워드/청크 의미/승인 항목 결과 |
| `id` | `ID` | Y | 결과 ID |
| `title` | `string` | Y | 결과 제목 |
| `snippet` | `string` | Y | 일부 본문 |
| `source` | [Source](00-common.md#schema-source) | N | 원문 근거. 출처 없는 HUMAN 승인 항목은 생략 |
| `design_item_id` | `ID` | N | 관련 항목 |
| `score` | `number` | N | 유사도. 확정 근거가 아님 |

<a id="schema-participant"></a>

### Participant

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `member_id` | `ID` | Y | 참여 멤버 |
| `status` | `INVITED\|ACCEPTED\|DECLINED` | Y | 참여 상태. 비비가 연 회의의 참여자는 ACCEPTED로 만든다(수정안 요청). DB의 JOINED와 값 이름을 맞춘다 |
| `role` | `LEADER\|TASK_OWNER\|DOMAIN_OWNER` | N | 비비가 부른 이유. 화면 예: '팀장 · TASK-001', 'TASK-002', '화면 문구'(담당 영역). 수정안 요청 |

<a id="schema-meetingentry"></a>

### MeetingEntry

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 ID |
| `meeting_id` | `ID` | Y | 회의 ID |
| `kind` | `TEXT\|BEE_ANALYSIS\|MINUTES\|CONFLICT_BRIEF\|SUMMARY` | Y | 기록 종류. CONFLICT_BRIEF(비비가 회의를 열며 쓴 첫 메시지)·SUMMARY(MTG-A11 정리)는 수정안 요청 |
| `text` | `string` | Y | 기록 본문 |
| `author_kind` | `HUMAN\|BEE` | Y | 작성자 종류. DB `meeting_entry.author_kind`와 같음. 수정안 요청 |
| `author_account_id` | `ID` | N | 작성 계정. 시스템 생성은 생략 |
| `author_member_id` | `ID` | N | HUMAN이면 작성 멤버. 수정안 요청 |
| `author_name` | `string` | N | HUMAN이면 표시 이름. 수정안 요청 |
| `refs` | `object[]` | N | 비비 기록이 가리키는 대상 [{type: TASK\|DESIGN_ITEM\|DOCUMENT_VERSION, id, label}]. 화면은 칩으로 보임. 수정안 요청 |
| `through_entry_id` | `ID` | N | 분석 마지막 입력 기록 |
| `generation` | [Generation](00-common.md#schema-generation) | N | AI 생성 근거 |
| `created_at` | `Time` | Y | 작성 시각 |

<a id="schema-meeting"></a>

### Meeting

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 회의 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `title` | `string` | Y | 제목 |
| `change_proposal_id` | `ID` | N | 연결 제안 |
| `origin` | `BEE_CONFLICT\|HUMAN` | Y | 연 주체. 수정안은 BEE_CONFLICT만 만든다. 수정안 요청 |
| `task_ids` | `ID[]` | Y | 충돌한 작업. 수정안 요청 |
| `conflict_summary` | `string` | N | 비비가 정리한 충돌 한 줄. 수정안 요청 |
| `status` | `OPEN\|CLOSED` | Y | 회의 상태. DB `meeting.status`와 같음. 수정안 요청 |
| `closed_at` | `Time` | N | 끝낸 시각. 수정안 요청 |
| `closed_by` | `ID` | N | 끝낸 팀장 계정. 수정안 요청 |
| `participants` | [Participant[]](00-common.md#schema-participant) | Y | 참여자 |
| `created_at` | `Time` | Y | 생성 시각 |

<a id="schema-actionitem"></a>

### ActionItem

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 후속 행동 |
| `member_id` | `ID` | N | 추천 담당자 |

<a id="schema-meetingminutes"></a>

### MeetingMinutes

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `entry` | [MeetingEntry](00-common.md#schema-meetingentry) | Y | 저장된 MINUTES 기록 |
| `summary` | `string` | Y | 요약 |
| `decisions` | `string[]` | Y | 합의 내용 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 후속 조치 |
| `unresolved` | `string[]` | Y | 미확정 항목 |

<a id="schema-assignmentsuggestion"></a>

### AssignmentSuggestion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | Y | 작업 |
| `member_id` | `ID` | Y | 추천 멤버 |
| `reason` | `string` | Y | 추천 이유 |

<a id="schema-relationnode"></a>

### RelationNode

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 객체 ID |
| `type` | `TASK\|DESIGN_ITEM\|VALIDATION\|CHANGE_PROPOSAL` | Y | 노드 종류 |
| `label` | `string` | Y | 표시 이름 |

<a id="schema-relationedge"></a>

### RelationEdge

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from_id` | `ID` | Y | 시작 객체 |
| `from_type` | `TASK\|DESIGN_ITEM\|VALIDATION\|CHANGE_PROPOSAL` | Y | 시작 종류 |
| `to_id` | `ID` | Y | 도착 객체 |
| `to_type` | `TASK\|DESIGN_ITEM\|VALIDATION\|CHANGE_PROPOSAL` | Y | 도착 종류 |
| `relation` | `string` | Y | 관계 |
| `confirmed` | `boolean` | Y | 명시적 확정 관계 |

<a id="schema-activity"></a>

### Activity

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 활동 ID |
| `type` | `string` | Y | 이벤트 종류 |
| `actor_type` | `HUMAN\|AGENT\|BEE\|SYSTEM` | Y | 행위자 종류. 공통 서비스 데이터의 actor_type. 수정안 요청 |
| `actor_account_id` | `ID` | N | 행위 계정 |
| `agent_session_id` | `ID` | N | AGENT면 세션. 수정안 요청 |
| `actor_name` | `string` | N | 사람 이름 또는 토큰 label. 수정안 요청 |
| `task_id` | `ID` | N | 대상 작업 |
| `change_proposal_id` | `ID` | N | 대상 제안 |
| `baseline_id` | `ID` | N | 대상 기준 |
| `result` | `string` | Y | 결과 |
| `created_at` | `Time` | Y | 발생 시각 |

<a id="schema-notification"></a>

### Notification

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 알림 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `type` | `string` | Y | 종류 |
| `title` | `string` | Y | 제목 |
| `body` | `string` | Y | 알림 내용 |
| `target_type` | `TASK\|VALIDATION\|CHANGE_PROPOSAL\|BASELINE\|MEETING\|DOCUMENT_VERSION` | Y | 이동 대상 종류. DOCUMENT_VERSION은 수정안 요청 |
| `target_id` | `ID` | Y | 이동 대상 |
| `document_id` | `ID` | N | DOCUMENT_VERSION이면 소속 문서. 수정안 요청 |
| `created_at` | `Time` | Y | 생성 시각 |
| `read_at` | `Time` | N | 읽은 시각 |

<a id="schema-usagebucket"></a>

### UsageBucket

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `key` | `string` | Y | 집계 구분 값 |
| `label` | `string` | N | 표시 이름. by_agent는 토큰 label 또는 '웹 직접 호출'. 수정안 요청 |
| `input_tokens` | `integer` | Y | 입력 토큰 |
| `output_tokens` | `integer` | Y | 출력 토큰 |
| `call_count` | `integer` | Y | 모델 호출 수 |
| `estimated_cost_amount` | `string` | N | 추정 비용 |

<a id="schema-usagesummary"></a>

### UsageSummary

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from` | `Time` | Y | 집계 시작. 포함 |
| `to` | `Time` | Y | 집계 끝. 미포함 |
| `input_tokens` | `integer` | Y | 총 입력 토큰 |
| `output_tokens` | `integer` | Y | 총 출력 토큰 |
| `call_count` | `integer` | Y | 중복 제거한 호출 수 |
| `estimated_cost_amount` | `string` | N | 추정 비용. 단가 없으면 생략 |
| `currency` | `string` | N | 통화 |
| `by_feature` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 기능별 집계 |
| `by_task` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 작업별. key는 task ID |
| `by_validation` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 검증별. key는 validation ID |
| `by_agent` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 에이전트별. key는 agent_session_id, 웹에서 직접 한 호출(문서 분석·비비 질문·회의 분석)은 key `WEB` 한 줄. 수정안 요청 |
| `by_day` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 날짜별. key는 한국 시간 기준 YYYY-MM-DD. 호출이 없는 날은 0으로 채움. 수정안 요청 |

<a id="schema-documentrejection"></a>

### DocumentRejection

수정안 요청. 공용 문서함의 팀장 반려 기록이다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 반려 ID |
| `reason` | `string` | Y | 팀장이 쓴 반려 사유 원문 |
| `rejected_by` | `ID` | Y | 반려한 팀장 계정 |
| `rejected_at` | `Time` | Y | 반려 시각 |
| `requested_content_hash` | `string` | Y | 반려된 요청본의 해시. 다시 요청할 때 '반려본 → 재요청본' 비교 기준 |

<a id="schema-documentreview"></a>

### DocumentReview

수정안 요청. 문서 버전마다 하나씩 도는 비비 필수 검토의 진행 상태다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 검토 대상 버전 |
| `mode` | `REVIEW\|RERUN` | Y | 등록 직후 검토 / 반려 사유를 반영한 재검토 |
| `status` | `QUEUED\|RUNNING\|COMPLETED\|FAILED` | Y | 실행 상태 |
| `step` | `integer` | Y | 지금 단계. 1부터 |
| `total_steps` | `integer` | Y | 전체 단계 수(REVIEW 5, RERUN 4) |
| `step_label` | `string` | Y | 단계 이름. 예: 'ERD와 맞춰 보기' |
| `progress_pct` | `integer` | Y | 0~100 |
| `eta_seconds` | `integer` | N | 남은 시간 추정. 모르면 생략 |
| `counts` | `object` | N | COMPLETED면 {major: integer, minor: integer} |
| `open_count` | `integer` | Y | 아직 반영·넘기기를 하지 않은 제안 수 |
| `applied_count` | `integer` | Y | 반영한 제안 수. 시작 가이드의 '고칠 곳 n개 반영' |
| `steps` | `object[]` | Y | 단계별 {label, description, result}. 화면은 단계마다 '문단 4개', '필드 5개'처럼 결과를 보임 |
| `error_code` | `string` | N | FAILED 원인 |
| `started_at` | `Time` | N | 시작 시각 |
| `completed_at` | `Time` | N | 끝난 시각 |

<a id="schema-reviewsuggestion"></a>

### ReviewSuggestion

수정안 요청. 비비가 문단에 남긴 수정 제안이다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `locator` | [SourceLocator](#schema-sourcelocator) | Y | 제안이 달린 문단의 행 범위(올린 원본 기준) |
| `category` | `OMISSION\|CONSISTENCY\|READABILITY\|REJECTION_FIX` | Y | 누락·일관성·가독성·반려 사유 반영 |
| `severity` | `MAJOR\|MINOR` | Y | 중요도 |
| `reason` | `string` | Y | 제안 이유 |
| `original_text` | `string` | Y | 바꿀 원문 |
| `proposed_text` | `string` | Y | 그 문단을 그대로 바꿀 수 있는 제안 문장 |
| `status` | `OPEN\|APPLIED\|SKIPPED` | Y | 처리 상태 |
| `origin` | `REVIEW\|DIRECT_REQUEST\|RERUN` | Y | 검토·'비비에게 직접 요청하기'·반려 후 재검토 |
| `rejection_id` | `ID` | N | RERUN이면 근거 반려 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 비교한 승인 문서 근거. 없으면 [] |
| `messages` | [ReviewMessage[]](00-common.md#schema-reviewmessage) | Y | 이 제안에 대한 비비와의 대화 |

<a id="schema-reviewmessage"></a>

### ReviewMessage

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 메시지 ID |
| `author_kind` | `HUMAN\|BEE` | Y | 작성자 |
| `text` | `string` | Y | 본문 |
| `proposed_text` | `string` | N | 비비가 대화 중 고친 제안 문장. 반영하면 제안의 proposed_text를 바꿈 |
| `created_at` | `Time` | Y | 작성 시각 |

<a id="schema-diffline"></a>

### DiffLine

수정안 요청. DOC-A06 비교 결과의 한 줄이다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `op` | `ADD\|DEL\|SAME` | Y | 추가·삭제·같음 |
| `text` | `string` | Y | 그 줄의 원문 |
| `suggestion_id` | `ID` | N | 비비 제안을 반영해 생긴 줄이면 그 제안 |
| `category` | `OMISSION\|CONSISTENCY\|READABILITY\|REJECTION_FIX` | N | suggestion_id가 있으면 그 분류 |

<a id="schema-meetingdocumentchange"></a>

### MeetingDocumentChange

수정안 요청. 회의를 끝낼 때 문서에 들어갈 변경 한 건이다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 바꿀 문서 |
| `base_version_id` | `ID` | Y | 정리 시점의 현재 승인본 |
| `locator` | [SourceLocator](#schema-sourcelocator) | Y | 바꿀 행 범위 |
| `before` | `string` | Y | 지금 문장 |
| `after` | `string` | Y | 회의를 끝내면 그대로 들어갈 문장 |
| `evidence_entry_ids` | `ID[]` | Y | 근거 발언. 이 회의의 실제 기록 ID만 |

<a id="schema-meetingsummary"></a>

### MeetingSummary

수정안 요청. 팀장이 요청한 비비의 회의 정리다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 정리 ID |
| `meeting_id` | `ID` | Y | 회의 |
| `entry` | [MeetingEntry](00-common.md#schema-meetingentry) | Y | 회의 기록에 남긴 SUMMARY 기록 |
| `through_entry_id` | `ID` | Y | 정리에 쓴 마지막 기록 |
| `conflicts` | `string[]` | Y | 충돌 |
| `decisions` | `object[]` | Y | 결정 {text, decided_by_member_id?}. 화면은 '이팀장이 정함'처럼 정한 사람을 붙임 |
| `document_changes` | [MeetingDocumentChange[]](00-common.md#schema-meetingdocumentchange) | Y | 바뀔 문서와 줄 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 할 일 |
| `previous_summary_id` | `ID` | N | 수정 요청으로 다시 만든 정리면 이전 정리 |
| `feedback` | `string` | N | 수정 요청 때 팀장이 쓴 고칠 점 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | AI 생성 근거 |
| `created_at` | `Time` | Y | 생성 시각 |

<a id="schema-beecitation"></a>

### BeeCitation

수정안 요청. 비비 답변의 [n] 표시와 이어지는 근거 문단이다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `n` | `integer` | Y | 답변 본문의 [n] 번호 |
| `document_id` | `ID` | Y | 문서 |
| `document_version_id` | `ID` | Y | 정확한 버전 |
| `version_no` | `integer` | Y | 버전 번호 |
| `title` | `string` | Y | 문서 제목 |
| `locator` | [SourceLocator](#schema-sourcelocator) | Y | 근거 행 범위 |
| `quote` | `string` | Y | 칠해서 보여 줄 근거 문장 |
| `section_title` | `string` | N | 근거가 든 절 제목. 화면 예: '3.2 초대 정책' |
| `claim` | `string` | N | AI가 이 근거로 뒷받침한 주장 |

<a id="schema-presence"></a>

### Presence

수정안 요청. 워크스페이스 HUD의 팀원 접속 상태다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `member_id` | `ID` | Y | 멤버 |
| `online` | `boolean` | Y | TTL 안에 보고가 있었는지 |
| `last_seen_at` | `Time` | N | 마지막 보고 시각 |
| `zone` | `string` | N | 사무실 안 위치. 예: desk, meeting_room, lounge |
| `seat` | `string` | N | 자리 식별값 |
| `panel` | `string` | N | 열어 둔 메뉴. 공개 범위는 Q18 |
| `activity` | `object` | N | 지금 하는 일 {action: VIEWING\|EDITING, target_type, target_id, label}. 화면 예: '변경 제안 검토 중', 'TASK-002 계획 작성 중'. 오프라인이면 마지막 값 |

<a id="schema-blockedproject"></a>

### BlockedProject

수정안 요청. 회원 탈퇴를 막는 프로젝트다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 프로젝트 |
| `name` | `string` | Y | 프로젝트 이름 |
| `my_role` | `LEADER` | Y | 탈퇴자의 역할. 지금 기준에서는 팀장만 막힘 |
| `member_count` | `integer` | Y | 본인 말고 활성 팀원 수. 화면 '팀장 · 팀원 1명' |

<a id="schema-changeditem"></a>

### ChangedItem

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_item_id` | `ID` | Y | 항목 |
| `before_version_id` | `ID` | N | 변경 전. 신규면 생략 |
| `after_version_id` | `ID` | N | 변경 후. 폐기면 생략 |

<a id="schema-publishedchange"></a>

### PublishedChange

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | Y | 발행 기준 |
| `proposal_id` | `ID` | N | 발행 제안. source=CHANGE_PROPOSAL이면 필수 |
| `source` | `CHANGE_PROPOSAL\|DOCUMENT_APPROVAL\|MEETING` | Y | 변경 출처. 문서함 승인(DOC-A21)·회의 끝내기(MTG-A13)도 변경으로 알림. 수정안 요청 |
| `document_version_ids` | `ID[]` | N | 바뀐 문서 버전. 수정안 요청 |
| `meeting_id` | `ID` | N | source=MEETING이면 그 회의. 수정안 요청 |
| `changed_items` | [ChangedItem[]](00-common.md#schema-changeditem) | Y | 항목별 전후 버전 |
| `affected_task_ids` | `ID[]` | Y | 영향 작업 |

<a id="schema-validationvalidity"></a>

### ValidationValidity

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `validation_id` | `ID` | Y | 검증 |
| `status` | `PASS\|STALE\|ERROR\|RUNNING\|NEEDS_INPUT\|NEEDS_REVIEW` | Y | 현재 유효 상태 |
| `reason` | `string` | N | 현재성 설명 |

<a id="schema-designchanges"></a>

### DesignChanges

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | Y | 현재 기준 |
| `items` | [PublishedChange[]](00-common.md#schema-publishedchange) | Y | 발행 변경 이력 |
| `validation_validity` | [ValidationValidity[]](00-common.md#schema-validationvalidity) | N | 작업 관련 검증 유효성 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 조회 cursor |

<a id="schema-findingcandidate"></a>

### FindingCandidate

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `check_key` | `string` | Y | 완료한 검사 키. ValidateResult.checked_keys 안에 있어야 함 |
| `kind` | `MISMATCH\|MISSING_INPUT\|MISSING_POLICY\|CONFLICT\|STALE_REF` | Y | 후보 분류 |
| `severity` | `INFO\|WARNING\|BLOCKING` | Y | 후보 중요도 |
| `message` | `string` | Y | 후보 설명 |
| `design_item_id` | `ID` | N | 관련 항목 |
| `design_item_version_id` | `ID` | N | 근거 항목 버전. design_item_id가 있으면 필수 |
| `related_task_id` | `ID` | N | 관련 작업 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `evidence_ids` | `string[]` | Y | 요청 evidence의 참조 키. content·계획만으로 확인한 경우 [] |
| `suggested_action` | `string` | Y | 후속 행동 제안 |

sources는 요청의 승인 항목·규칙 finding·evidence에 전달된 출처만 참조한다. evidence_ids가 있으면 그 근거의 source도 sources에 포함한다. 원문 근거가 없는 필수 계획 누락은 sources/evidence_ids=[]로 표현할 수 있고 누락된 계획 내용을 message에 명시한다. 후속 경로·현재 담당자·최종 판정·영구 ID는 backend가 연결한다.

<a id="schema-parseditem"></a>

### ParsedItem

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `candidate_key` | `string` | Y | AI 결과 내 임시 키. 최종 ID 아님 |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 항목 종류 |
| `schema_version` | `integer` | Y | 본문 구조 버전 |
| `content` | `object` | Y | kind별 본문 Q01 |
| `location` | `string` | Y | 원문 위치 |

<a id="schema-promptquestion"></a>

### PromptQuestion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `question` | `string` | Y | 보완 질문 |
| `location` | `string` | Y | 근거 위치 |

<a id="schema-embeddingtext"></a>

### EmbeddingText

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `key` | `string` | Y | 요청 안에서 유일한 텍스트 키 |
| `text` | `string` | Y | 임베딩 대상 텍스트 |

<a id="schema-embeddingvector"></a>

### EmbeddingVector

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `key` | `string` | Y | 입력 텍스트 키 |
| `embedding` | `number[]` | Y | 모델 차원과 같은 길이의 벡터 |

<a id="schema-documentcount"></a>

### DocumentCount

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 문서 종류 |
| `draft_count` | `integer` | Y | 미승인 초안 수 |
| `approved_count` | `integer` | Y | 현재 승인 문서 수 |

<a id="schema-statecount"></a>

### StateCount

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `string` | Y | 해당 API가 집계하는 상태 |
| `count` | `integer` | Y | 건수 |

<a id="schema-waitingcount"></a>

### WaitingCount

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `waiting_reason` | `string` | Y | 대기 이유 |
| `count` | `integer` | Y | 건수 |

<a id="schema-designsuggestion"></a>

### DesignSuggestion

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_item_version` | [DesignItemVersion](00-common.md#schema-designitemversion) | Y | 추천 승인 항목 |
| `reason` | `string` | Y | 추천 근거 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |

<a id="schema-dependencycandidate"></a>

### DependencyCandidate

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from_design_item_id` | `ID` | Y | 의존 후보 시작 항목 |
| `to_design_item_id` | `ID` | Y | 의존 후보 대상 |
| `reason` | `string` | Y | 후보 근거 |
| `confirmed` | `boolean` | Y | 확정 관계가 아님 |

<a id="schema-comparison"></a>

### Comparison

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `target_type` | `DOCUMENT\|DESIGN_ITEM` | Y | 비교 대상 |
| `target_id` | `ID` | Y | 대상 |
| `format` | `TEXT\|FILE\|STRUCTURED` | Y | 비교 형식 |
| `base_content` | `object\|string` | N | 기준 본문. FILE에서는 생략 |
| `current_content` | `object\|string` | N | 현재 본문. FILE에서는 생략 |
| `proposed_content` | `object\|string` | N | 수정안. FILE에서는 생략 |
| `diff` | `string` | N | 텍스트/구조 차이. FILE은 생략 |
| `base_document_version_id` | `ID` | N | FILE 기준 버전 |
| `proposed_document_version_id` | `ID` | N | FILE 수정안 버전 |

<a id="schema-parseresult"></a>

### ParseResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ParsedItem[]](00-common.md#schema-parseditem) | Y | 임시 키를 가진 설계 후보 |
| `questions` | [PromptQuestion[]](00-common.md#schema-promptquestion) | Y | 보완 질문 |
| `missing_candidates` | `string[]` | Y | 누락 검토 후보 |

<a id="schema-validateresult"></a>

### ValidateResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `analysis_status` | `COMPLETE\|PARTIAL` | Y | 요청된 의미 검사의 수행 범위. 최종 검증 판정이나 문서 파싱 상태가 아님 |
| `checked_keys` | `string[]` | Y | 분류·근거 검사까지 수행한 요청 검사 키 |
| `unassessed` | [UnassessedValidationCheck[]](00-common.md#schema-unassessedvalidationcheck) | Y | 수행하지 못한 요청 검사와 이유 |
| `findings` | [FindingCandidate[]](00-common.md#schema-findingcandidate) | Y | 의미 분석 후보 |

checked_keys와 unassessed의 키는 요청 checks를 정확히 분할하고, findings는 checked_keys에 속한 검사에서만 반환한다. unassessed가 비면 COMPLETE, 하나라도 있으면 PARTIAL이다. COMPLETE는 검사를 마쳤다는 뜻이므로 MISSING_INPUT·MISSING_POLICY나 불일치 후보가 있어도 가능하다. 빈 findings만으로 PASS를 판정하지 않는다.

HTTP 200의 COMPLETE/PARTIAL은 실제 모델 호출 후에만 반환한다. backend는 구조·검사 키·ID·출처를 검증하고 6절 판정표를 적용한다. 이 검토안은 API DTO의 진단 필드이며 DDL의 finding 종류·실행 상태를 변경하지 않는다.

저장 위치와 매핑은 **S15P31A108-159에서 확정**한다. Q02 리뷰의 저장안은 checks·checked_keys·unassessed를 적용 attempt/run과 함께 기존 `validation.summary` JSONB에 보관하고, 실패 원인을 `validation_run.error`와 해당 실행의 source=MODEL·kind=ERROR finding으로 추적하는 것이다. `validation`에는 error_code DB 컬럼이 없으며 외부 Validation DTO의 error_code는 [검증 실행 오류 규약](#validation-execution-errors)에 따라 현재 실패 attempt의 정규화된 분류에서 조립한다. FindingCandidate.check_key·evidence_ids도 현재 validation_finding에 컬럼이 없으므로 -159의 새 마이그레이션에서 영속화 구조를 확정한다. 마이그레이션 번호는 다른 변경과 겹치지 않도록 -159에서 머지 직전에 정한다. 이번 문서 변경은 기존 V2 DDL을 수정하지 않는다.

<a id="schema-impactresult"></a>

### ImpactResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `candidates` | [ImpactCandidate[]](00-common.md#schema-impactcandidate) | Y | 영향·담당자 후보 |

<a id="schema-embeddingresult"></a>

### EmbeddingResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `model` | `string` | Y | 사용 임베딩 모델 |
| `dimension` | `integer` | Y | 벡터 차원. 아래 예시는 설명용 3 |
| `vectors` | [EmbeddingVector[]](00-common.md#schema-embeddingvector) | Y | 입력 key별 벡터 |
| `embedding_profile_id` | `string` | 조건부 | 확장 요청에서 실제 서버 프로필 ID |
| `purpose` | `DOCUMENT\|QUERY` | 조건부 | 확장 요청에서 요청 용도 |

<a id="schema-chatresult"></a>

### ChatResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 설계 기반 답변 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `unresolved` | `string[]` | Y | 미확정 |
| `next_actions` | `string[]` | Y | 권장 후속 행동 |
| `citations` | `object[]` | 조건부 | 원문 RAG 필수. 각 원소 {evidence_id: string, claim: string}. 입력 근거 집합 밖 ID 금지, 인용 없음은 [] |

<a id="schema-draftresult"></a>

### DraftResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `after_content` | `string` | Y | 미승인 수정 원문 |
| `reason` | `string` | Y | 생성 이유 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `unresolved` | `string[]` | Y | 미확정 |

<a id="schema-assignmentsresult"></a>

### AssignmentsResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `suggestions` | [AssignmentSuggestion[]](00-common.md#schema-assignmentsuggestion) | Y | 담당 후보와 이유 |

<a id="schema-meetinganalysisresult"></a>

### MeetingAnalysisResult

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 질문 또는 요약 |
| `decisions` | `string[]` | Y | 결정 요약 |
| `unresolved` | `string[]` | Y | 미확정 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 후속 조치 |
| `conflicts` | `string[]` | N | SUMMARY 필수. 충돌. 수정안 요청 |
| `document_changes` | [MeetingDocumentChange[]](00-common.md#schema-meetingdocumentchange) | N | SUMMARY 필수. 바뀔 문서와 줄. 수정안 요청 |

<a id="schema-sourcelocator"></a>

### SourceLocator — 원문 RAG

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `text_lines` | Y | md/txt MVP. JSON Pointer는 입력 확장 시 별도 도입 |
| `start_line` | `integer` | Y | utf8-lf-v1 정규화 텍스트의 1-based 시작 행 |
| `end_line` | `integer` | Y | 끝 행 포함, start_line 이상 |

기본 청크는 원문의 연속 행 범위다. 긴 한 행/분리 범위 등의 정확한 locator 확장이 필요하면 조용히 누락하거나 잘못된 정밀도를 표시하지 말고 invalid_ai_input 진단으로 실패시킨다. 행 개수·해시·청크 원문 일치 검증을 함께 수행한다. 파서 블록 구조는 내부 구현이고 BE 저장 청크의 최소 계약과 구분한다.

<a id="schema-retrievalscope"></a>

### RetrievalScope

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `mode` | `APPROVED\|DOCUMENT_REVIEW` | Y | 개발 기준 답변 또는 명시 문서 검토 |
| `document_version_ids` | `ID[]` | Y | BE 권한 검사로 결정한 정확한 문서 버전 집합 |
| `baseline_id` | `ID` | 조건부 | APPROVED 필수, DOCUMENT_REVIEW에서는 생략 |

APPROVED의 허용 집합은 project.current_baseline_id의 baseline_document/baseline_item에서 정한다. DOCUMENT_REVIEW는 명시 버전만 허용하고 미승인 검토로 표시한다. 사용자 입력 목록은 권한 증명이 아니다. 필수 4종 READY를 모두 요구할지의 가용성 정책은 후속 합의이며, 일부 검색 결과를 전체 기준의 완전한 답변으로 표시하지 않는다.

<a id="schema-retrievedevidence"></a>

### RetrievedEvidence

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `evidence_id` | `string` | Y | 이번 답변 입력 집합에서 유일한 근거 ID |
| `document_id` | `ID` | Y | 소속 문서 |
| `document_version_id` | `ID` | Y | scope 안의 정확한 버전 |
| `version_no` | `integer` | Y | 문서의 버전 번호 |
| `chunk_key` | `string` | Y | 파싱/임베딩 대응 키 |
| `text` | `string` | Y | 전달할 원문 청크 |
| `locator` | [SourceLocator](#schema-sourcelocator) | Y | 정규화 원문 위치 |
| `embedding_profile_id` | `string` | Y | 검색에 사용한 동일 활성 프로필 |

DB 저장·권한/승인 SQL 필터·실행/manifest 담당은 오서현, 파싱·청킹·embedding profile/검색 전략·답변은 박상은이다. SQL은 허용 집합/READY/프로필을 먼저 제한한 뒤 top-k를 계산한다. 전역 top-k 후 사후 필터로 대체하지 않는다. 재시도 attempt/hash/profile 검증과 청크 전량+READY 게시를 원자적으로 묶으며 새 DB 구조는 후속 migration으로 별도 반영한다.
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
