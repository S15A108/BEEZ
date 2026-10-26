# 검증 AI 계약 예시

S15P31A108-107의 `/v1/validate` 검토·병렬 개발용 입력과 기대 응답이다. [AI-A02](../../03-ai-internal.md#ai-a02)와 [공통 스키마](../../00-common.md#schema-validateresult)를 따른다.

이 예시는 합성 데이터이며 실제 파서·LLM·검증 서버를 실행한 결과가 아니다. `origin=MODEL`, `model`, `usage`는 모델 응답의 형태를 보여주는 임시 값이다. 토큰 수·비용은 만들지 않았으며, usage의 지연 값도 실측값이 아니다. 개발용 대역에서만 사용하고 운영 분석 이력·사용량으로 저장하지 않는다.

## 파일과 사용 방법

| 파일 | 내용 |
| --- | --- |
| [source-login-api.md](source-login-api.md) | 정확히 8행인 설명용 원문 |
| [request.json](request.json) | 작업 501의 계획과 승인 설계·작업 502의 계획을 비교하는 기준 입력 |
| [responses.json](responses.json) | 입력별 변경 사항, HTTP 응답, BE 기대 상태 6종 |

`responses.json`의 `cases`에서 하나를 골라 다음처럼 사용한다.

1. `request.json`을 깊은 복사한다.
2. 해당 case의 `request_patch`를 순서대로 적용한다. 표준 JSON Patch의 `replace` 연산만 사용하며, `path`는 JSON Pointer다. 예를 들어 `/plan_revision/plan/approach`는 해당 문자열을 `value`로 교체한다. 기준 입력에 없는 경로를 만들거나 배열을 암묵적으로 병합하지 않는다.
3. 수정한 입력을 해당 case의 요청으로 사용한다. `mismatch`는 패치가 없어 기준 입력을 그대로 사용한다.
4. 대역이 필요하면 `http_status`와 `response`를 그대로 반환하도록 설정하고, BE가 `expected_backend_status`로 매핑하는지 확인한다. `expected_error_finding_resolution`이 있으면 BE가 현재 실패 attempt에 만든 `kind=ERROR` finding의 `resolution`도 확인한다. 이 필드들과 `request_patch`, `description`, `synthetic_fixture`는 예시 메타데이터이며 실제 AI 요청·응답 본문에 포함하지 않는다.

각 case는 독립된 상황이다. timeout·잘못된 출력은 같은 업무 입력에 대한 서로 다른 실행 결과이며, 문서 내용만으로 발생 여부를 결정하는 규칙이 아니다. 입력 변경 case의 새 검증 ID·요청 키·스냅샷 해시는 설명용이다. 실제 BE는 바뀐 입력에 새 검증을 만들고 자신의 정규화·해시 계약으로 스냅샷 해시를 발급한다. 이 예시가 해시 산출 알고리즘을 정의하지 않는다.

`usage[].request_key`는 실제 모델 호출 한 번을 식별한다. 예시의 `llm-validate-{validation_id}-attempt-{attempt}-call-{k}`에서 같은 실제 호출의 응답을 재전송할 때는 같은 키를 유지하고, 새 실제 호출에는 새로운 순번의 키를 부여한다. `compatible`은 한 검증 attempt에서 두 번 호출한 상황을 합성해 `call-1`·`call-2`를 함께 담았다. 호출 횟수·지연 값은 실제 실행 기록이 아니며 토큰 사용량은 미확인 상태다.

## 상황별 기대 처리

| case | 달라지는 상황 | AI HTTP 응답 | BE 기대 상태 |
| --- | --- | --- | --- |
| `compatible` | 본문의 accessToken·userId를 유지하는 계획 | 200, COMPLETE, 모든 검사 수행, findings 없음 | PASS |
| `mismatch` | 본문 토큰을 제거하는 계획과 이를 읽는 FE 계획 | 200, COMPLETE, 불일치·충돌 후보 | NEEDS_REVIEW |
| `missing_plan_input` | 계획에 토큰 전달 방식이 없어 작성자 보완 필요 | 200, COMPLETE, MISSING_INPUT | NEEDS_INPUT |
| `insufficient_evidence` | 승인 content의 응답 세부 정보와 원문 발췌가 없어 일부 비교 불가 | 200, PARTIAL, 미검사 목록 | ERROR / HUMAN_REVIEW |
| `timeout` | 실제 모델 호출 후 요청 예산 안에 분석을 끝내지 못함 | 504 / model_timeout | ERROR / RETRY |
| `invalid_model_output` | 모델 출력이 약속된 구조·검사 범위 검증을 통과하지 못함 | 502 / model_unavailable | ERROR / RETRY |

`COMPLETE`는 지정된 검사 수행을 마쳤다는 뜻이다. 필수 계획 정보 누락을 발견하고 보완 요청을 만든 경우에도 COMPLETE일 수 있다. 문제가 없는지와 검사 완료 여부를 구분한다. PASS는 기존 규칙 finding·최신성·지정 검사 수행 조건을 함께 확인한 BE가 결정한다.

`insufficient_evidence`의 요청은 형식상 유효한 `content` object다. 모델은 계획 확인을 수행했지만, 응답 계약의 세부 내용이 부족해 나머지 비교를 수행하지 못했다고 보고한다. 응답 필드의 구체 구조는 Q01 확정 전 검토용 예시다. 호출 전에 알 수 있는 미지원 kind/schema·잘못된 참조는 422 대상이며, 이 case처럼 의미 분석 후 발견한 근거 부족과 구분한다. `PARTIAL`을 파싱·색인의 부분 성공 상태로 사용하지 않는다.

ERROR의 재시도 가능 여부는 BE가 **현재 실패 attempt**에 생성한 `kind=ERROR` finding의 `resolution`으로 구분한다. 이 예시에서 근거 부족은 `HUMAN_REVIEW`이므로 같은 입력으로 재시도할 수 없으며, 근거를 보완한 뒤 새 검증을 만든다. timeout·잘못된 출력은 `RETRY`로 분류해 현재 실패 attempt를 명시적으로 재시도할 수 있다. 과거 attempt의 `RETRY` finding을 현재 실패의 재시도 근거로 사용하지 않으며, 현재 실패 attempt에 `HUMAN_REVIEW`가 있으면 재시도를 허용하지 않는다. 이 finding은 BE가 생성하며 AI의 `FindingCandidate.kind`에 `ERROR`를 추가하지 않는다.

## 근거와 식별자

- 기준 입력의 작업은 `501`·계획 리비전 `511`, 다른 작업은 `502`·리비전 `512`다. 둘을 같은 계획으로 넣지 않는다.
- 기준 계획은 `IMPLEMENTS`와 `no_contract_change=true`를 선언했지만, 접근 방식에 본문 토큰 제거를 적었다. 명시적인 CHANGES 점유 충돌을 BE가 먼저 차단하는 예시 대신, 의미 분석에서 발견할 불일치 상황을 보여준다.
- 관련 작업 502의 관계는 `READS`다. 현재 입출력 예시는 계약을 소비하는 작업에 미치는 영향까지 검토하는 용도이며, Q02의 관련 계획·`checks` 자동 선정 범위는 담당자와 최종 합의 전이다. 같은 설계를 `IMPLEMENTS`·`CHANGES`하는 열린 작업만 자동 선정하는 제안과 이 소비 영향 예시의 적용 범위를 별도로 확인해야 한다.
- `source-login-api.md`는 LF 끝 개행을 포함한다. `utf8-lf-v1` 정규화 후 1부터 시작하는 3~8행을 `evidence.text`에 그대로 담았다. 끝 행과 각 행의 LF도 포함한다.
- `evidence.text_hash`는 발췌문이 아닌 **전체 정규화 원문** UTF-8 bytes의 SHA-256이다. 업로드 원본 bytes의 `content_hash`와 역할이 다르다. 이 파일에서는 BOM·CRLF가 없어 두 해시가 우연히 같을 수 있지만 이를 일반 규칙으로 검사하지 않는다.
- `checked_keys`와 `unassessed[].check_key`는 요청의 `checks[].key`를 중복·누락 없이 나눈다. finding은 완료한 검사와 요청 안의 항목·작업·근거 ID만 참조한다.
- 담당자는 AI가 생성하지 않는다. BE가 `related_task_id`와 현재 작업·멤버 정보를 연결한다. 기존 RULE finding을 해제하거나 낮추는 응답도 만들지 않는다.
- `execution_budget_ms=30000`은 예시 값이며 운영 SLA·배포 설정의 합의를 뜻하지 않는다.
