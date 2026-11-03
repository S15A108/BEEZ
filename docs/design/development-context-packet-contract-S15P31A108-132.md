# 개발 컨텍스트 패킷 구성 계약 (#132)

작성: 박상은 · Jira: S15P31A108-132 · 대상: 전진(MCP 어댑터 -179), 변준성(패킷 저장 -166)

**제안 상태다.** MCP-01 `get_development_context`의 패킷 **내용**을 #131 선택 결과와 BE가 제공한 메타데이터로 구성하는 AI 쪽 계약을 적는다. 도구 인수·응답 모양은 [MCP Tools MCP-01](../api/02-mcp-tools.md#mcp-01), BE 서비스 입구는 [서비스 연결 계약 3절](08-mcp-service-contract.md)이 기준이다. 이 문서는 "AI가 무엇을 받아 무엇을 돌려주는가"만 정하며, 권한·현재성 검증·패킷 저장·`content_hash`는 계속 BE가 맡는다. 실제 MCP-01 호출 확인과 BE→AI 호출 방식 합의는 아직 남았다.

## 1. 흐름과 책임

1. BE `ContextMcpService.getDevelopmentContext`가 권한·인수(400/403/404)를 확인하고 현재 기준의 승인 설계·원문 근거·확정 관계·작업 계획 참조를 모아 #131 스냅샷(`SelectionInput`)과 아래 `metadata`를 만든다.
2. AI는 스냅샷으로 선택·의존 확장을 수행하고(#131), `metadata`와 합쳐 패킷 내용을 돌려준다. ID·출처·제약·승인은 만들지 않고 원문도 복사하지 않는다.
3. BE는 선택에 사용한 `baseline_id`를 저장 입력의 `expected_baseline_id`로 전달한다. `ContextPacketService.save`는 프로젝트 행 공유 잠금 아래 현재 기준과 비교해 다르면 409 `baseline_conflict`로 저장을 거부하고, 같으면 `design_item_version_ids`·`sources`·`related_tasks`도 검증한 뒤 불변 패킷을 저장하고 `content_hash`를 계산한다. 색인·구성 모듈 미준비는 503 `context_not_ready`로 끝내고 패킷을 저장하지 않는다. 검색을 마친 0건은 정상 응답의 `unresolved`다.

구현: `ai/app/schemas/context_packet.py`(모델), `ai/app/services/retrieval/context_packet.py`(`compose_development_context`, `check_packet_metadata`), HTTP는 기존 `POST /v1/development-context-selections`([#131 초안](../experiments/context-selection-http-S15P31A108-131.md))에 선택적 `metadata`를 더한 형태다. 예시: `ai/examples/context-packet-132/`.

## 2. 입력: 스냅샷 + `metadata`

요청 본문은 #131과 같고 `metadata`가 추가된다. `metadata`가 없으면 응답은 선택 결과만 담는다(기존 동작). `metadata`의 설계·근거는 **스냅샷과 정확히 같은 집합**이어야 한다. HTTP 경로는 불일치와 필수 설명 누락을 모델 호출 전에 400 `validation_failed`로 거부한다. 프로세스 내부 호출자는 모델 호출 전에 `check_packet_metadata`를 직접 호출해야 한다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_items[]` | DesignItemMeta | Y | 스냅샷 `items`의 모든 버전. `design_item_version_id`·`design_item_id`가 스냅샷과 일치해야 한다 |
| `design_items[].key` | string | Y | 표시 키(예: `API-001`) |
| `design_items[].kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 00-common DesignItemVersion.kind |
| `design_items[].version_no` | integer ≥ 1 | Y | 항목 버전 번호 |
| `design_items[].source` | Source | N | 항목의 원문 출처. 현재 기준 소속이 확인되지 않은 참고 값이며 패킷 `sources`에는 합치지 않는다 |
| `design_items[].description` | string ≤ 10,000 | 조건부 | 승인 본문의 `content.description`. `POLICY`·`CONSTRAINT`는 공백이 아닌 설명 필수이며 모델 호출 전에 검증한다. 그 외 종류는 생략할 수 있다 |
| `evidence[]` | EvidenceMeta | Y | 스냅샷 `evidence`의 모든 후보. `evidence_id`와 `document_version_id`가 일치해야 한다 |
| `evidence[].document_id`, `version_no`, `location` | — | Y | 해당 청크의 00-common Source 값 |
| `task` | TaskMeta | 조건부 | `task_id`가 없으면 금지. 계획이 없거나 작업이 `DRAFT`이면 생략; 계획이 있고 `READY`·`IN_PROGRESS`·`WAITING`·`COMPLETED`·`CANCELLED` 중 하나이면 `task_id`·현재 `plan_revision_id`·`status`를 포함 |

`task_id`는 계획이 없거나 `DRAFT`여도 요청의 작업 식별자로 유지한다. AI는 생략된 작업 메타데이터만으로 실제 상태·계획 유무를 독립적으로 확인할 수 없다. BE는 상태와 계획을 확인해 입력을 구성하고 사용·저장 전 재확인해야 한다. 현재 BE 저장 서비스가 빈 `related_tasks`에 대해 이를 재검사한다는 뜻은 아니며 실제 연동에서 계약·경합 처리가 필요하다.

Source는 00-common과 같다: `document_id`, `document_version_id`, `version_no`(≥1), `location`(1~1,000 코드 포인트). ID는 공백 없는 1~256자 문자열이다.

## 3. 출력: `packet`

성공 응답은 `{"result": <#131 선택 결과>, "usage": [...], "packet": <아래>}`다. `packet`은 MCP-01 응답의 내용 부분이며 `id`·`created_at`·`content_hash`는 BE 저장 후에 붙는다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `project_id`, `baseline_id`, `task_id` | ID | 스냅샷 값 그대로. `task_id`는 null 가능 |
| `request` | string ≤ 2,000 코드 포인트 | 개발 요청 원문 |
| `search_status` | `COMPLETE` | 미준비·실패는 오류로 끝나므로 항상 COMPLETE |
| `request_summary` | object \| null | 요청 분석 힌트(`functions`·`domains`·`keywords`). HTTP 경로는 분석기를 연결하지 않아 null이다. 필터나 권한이 아니다 |
| `design_items[]` | PacketDesignItem | 선택된 승인 설계. 스냅샷 `items` 순서. `reasons`는 `MATCHED`(직접 일치), `TASK_PLAN`(현재 계획 참조), `DEPENDENCY`(확정 명시 의존 1단계) 중 1개 이상 |
| `design_items[].source` | Source \| null | `metadata`의 항목 **저장 출처** 그대로. 위치와 현재 기준 소속이 확인되지 않은 참고 값이며 `sources`에는 합치지 않는다 |
| `design_item_version_ids[]` | ID | `design_items`와 같은 순서의 버전 ID. BE `SavePacketArgs.designItemVersionIds` |
| `constraints[]` | string ≤ 500 | 선택된 `POLICY`·`CONSTRAINT` 항목의 `description`을 순서대로. 500 코드 포인트 초과는 뒤를 잘라 `…`를 붙인다 |
| `unresolved[]` | string ≤ 500 | BE 저장용 요약 문자열. 선택 상태는 코드 그대로(`NO_MATCH`, `DEPENDENCY_UNVERIFIED`), 검토 후보는 `"<KIND>: <description>"`을 500 코드 포인트로 자른다. 직접 선택 상한으로 제외된 후보가 있으면 `SELECTION_TRUNCATED: 직접 일치 후보 중 설계 N건, 원문 M건이 직접 선택 상한으로 제외됐습니다.`를 넣어 저장 패킷에도 잘림 사실이 남는다 |
| `unresolved_details[]` | UnresolvedDetail | `unresolved`와 같은 순서의 구조형. `kind`(`NO_MATCH\|DEPENDENCY_UNVERIFIED\|POSSIBLE_CONFLICT\|INSUFFICIENT_EVIDENCE\|SELECTION_TRUNCATED`), 전체 `description`(≤ 2,000), `references[]`(`kind`=`design_item\|evidence`, `id`). 인용문은 넣지 않는다 |
| `related_tasks[]` | RelatedTask | `metadata.task`가 있으면 요청의 작업 1건, 없으면 빈 목록. 다른 작업 검색은 범위 밖 |
| `sources[]` | Source | **선택된 원문 근거의 출처만**(선택 순서). 근거의 문서 버전은 스냅샷 `document_version_ids`(현재 기준 문서)에 속하므로 BE의 `requireSourceInBaseline` 검증을 통과한다. 설계에 저장된 출처는 현재 기준 밖 문서 버전이거나 위치가 미확인일 수 있어 넣지 않는다. 같은 (document_id, document_version_id, version_no, location)은 한 번만 |
| `direct_match_truncation` | object \| null | #131과 같음. 직접 선택 상한으로 제외된 후보 수 |

규칙:

- `design_items`가 비고 `evidence`도 없으면 `unresolved`에 `NO_MATCH`가 있다. 작업 참조·의존 항목만 있어도 `NO_MATCH`는 유지된다(#131).
- 미확정 검토 후보는 `review_unresolved`를 통과한 결과만 받는다. 선택되지 않은 ID를 가리키거나 형식이 다르면 400 `validation_failed`다.
- 선택 결과는 같은 스냅샷에서 나온 것이어야 한다(내부 일치 표식 검사). 직렬화된 선택 결과는 다시 쓸 수 없다.

## 4. BE 저장 입력 매핑

`DevelopmentContextPacket.to_save_packet_args()`는 BE `SavePacketArgs`와 같은 snake_case JSON을 만든다. 예시: `ai/examples/context-packet-132/save-packet-args.json`.

| AI `packet` | BE `SavePacketArgs` | BE 재검증 |
| --- | --- | --- |
| `request` | `request` | 공백 금지(400) |
| `project_id`, `task_id` | `projectId`, `taskId` | 토큰 프로젝트 일치(404), 작업 소속(404) |
| `baseline_id` | `expectedBaselineId` | 프로젝트 공유 잠금 아래 현재 기준과 비교. 다르면 409 `baseline_conflict` |
| `design_item_version_ids` | `designItemVersionIds` | 모두 현재 기준 설계(400), 기준 없음(404 `baseline_not_published`) |
| `constraints` | `constraints` | — |
| `unresolved` | `unresolved` | — |
| `related_tasks` | `relatedTasks` | 현재 계획 리비전·상태와 일치(400) |
| `sources` | `sources` | 현재 기준 문서 버전·번호와 일치(400) |

`content_hash`는 BE가 `{project_id, task_id, request, baseline_id, content}`의 정규 JSON(키 정렬·목록 순서 유지)으로 계산한다. AI는 해시를 만들지 않으며, 해시 일치만으로 실제 전송 사실을 증명하지 않는다. `design_items`·`unresolved_details`·`request_summary`는 저장 입력에 넣지 않는다. `direct_match_truncation`도 구조로는 넣지 않지만, 제외된 후보가 있으면 `unresolved`의 `SELECTION_TRUNCATED` 항목으로 보존해 저장 패킷을 나중에 읽는 쪽이 누락 사실을 알 수 있게 한다. 구조 필드 추가 여부는 9절.

### 선택 이유 저장 제안 (리뷰 반영, BE 합의 전)

선택 이유는 설계 버전의 고정 속성이 아니라 해당 요청에서의 선택 결과이므로 저장 패킷에 버전별로 보존하는 것이 필요하다. 현재 `packet.design_items[].reasons`에는 값이 있으나 현재 9개 필드의 `SavePacketArgs`와 저장 응답에는 없어 MCP-01·MCP-06 호출자는 볼 수 없다. **현재 변환 함수에 새 필드를 보내지는 않는다.** 다음은 BE 저장 담당자와 합의할 제안이며 아직 구현·연동 완료가 아니다.

```json
{
  "selection_reasons": [
    {"design_item_version_id": "1101", "reasons": ["MATCHED", "TASK_PLAN"]},
    {"design_item_version_id": "1102", "reasons": ["DEPENDENCY"]}
  ]
}
```

- 새 필드는 패킷 수준에 두고, 저장 시 선택된 `design_item_version_ids`와 버전별 1:1인지, ID·이유 중복이 없는지, 이유가 `MATCHED`·`TASK_PLAN`·`DEPENDENCY` 중 하나 이상인지 검증한다. BE는 현재 기준의 설계 버전 검증도 계속 수행한다.
- BE 저장 `content`와 `content_hash`에 포함하고, MCP-01 저장 응답과 MCP-06 패킷 재조회에서 동일한 값을 반환하도록 저장 입력·응답 계약을 함께 확장한다.
- 과거 패킷의 미기록은 이유가 없는 것으로 단정하지 않는다. 기존 저장본을 보존하면서 미기록을 구분하는 호환 표현은 BE·MCP 담당자와 정한다. `unresolved_details`와 `request_summary` 저장 범위도 별도 합의한다.

## 5. 조회 수준과 원문 (POL-02, #95)

- 패킷은 MCP-01과 `read_context(PACKET)`으로만 조회하고 개인 대화 원문은 제공하지 않는다. 별도 summary/detail 파라미터는 두지 않는다.
- `constraints`·`unresolved` 항목은 #95의 summary 상한 500 코드 포인트를 따른다. 설계 본문·원문 전체(detail)는 `get_document`·`get_design_changes` 등 기존 경로에서 ID·버전으로 조회한다.
- 패킷에는 설계·원문 후보 텍스트, 검토 인용문, 타인 계획 본문을 넣지 않는다. 출처는 문서 ID·버전·위치만 담는다.

## 6. 크기 상한과 잘림

| 대상 | 상한 | 초과 시 |
| --- | --- | --- |
| 요청 본문 | 262,144바이트 | 413 `request_too_large` (수신 중 차단) |
| 승인 설계·원문 후보·작업 참조 | 종류별 200개 | 400 `validation_failed` |
| `constraints[]`·`unresolved[]` 항목 | 500 코드 포인트 | 뒤를 잘라 `…`. 전체 설명은 `unresolved_details` |
| `request` | 2,000 코드 포인트 | 400 `validation_failed` (공용 명세 반영은 담당자 확인 필요) |
| 구성한 `packet` JSON | 262,144바이트 | 422 `packet_too_large`. ID·근거를 지워 맞추지 않는다 |

HTTP 경로에서는 본문 상한이 먼저 걸리므로 `packet_too_large`는 사실상 프로세스 내부 구성(`compose_from_pipeline`)에서만 난다. BE 저장 시 설계 본문 스냅샷이 더해지므로 MCP 응답의 최종 크기와 잘림·전체 조회 경로는 BE와 별도로 정한다(#95 2.2절).

## 7. 오류 응답

형식은 공통 오류 봉투 `{"error": {"code", "message"}}`이며 실제 공급자 호출이 있었으면 `usage`를 덧붙인다. 예시: `ai/examples/context-packet-132/errors.json`. 상태는 REST 대응 의미이고 어댑터는 `code`·`message`를 도구 실패 결과로 옮긴다.

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `invalid_internal_token` | AI 내부 토큰 누락·불일치 |
| 503 | `selection_not_configured` | AI 선택 설정 미지정(기본 비활성) |
| 400 | `validation_failed` | JSON·필드·범위 오류, `metadata`와 스냅샷 불일치, 선택 범위 밖 검토 후보 |
| 415 / 413 | `unsupported_media_type` / `request_too_large` | 미디어 타입·본문 크기 |
| 503 | `context_not_ready` | `search_status=NOT_READY`. BE는 패킷을 저장하지 않는다 |
| 502 | `search_failed` | `search_status=FAILED`. 근거 0건과 구분 |
| 504 | `embedding_timeout` | 선택 시간 예산 초과. 시작된 호출 `usage` 포함 |
| 422 | `embedding_limit_exceeded` 등 | #131 입력 한도 오류 |
| 422 | `packet_too_large` | 6절 |
| 502 | `selection_failed`, `invalid_selection` 등 | AI 쪽 실패. 입력 거부가 아니다 |

BE 쪽 403 `forbidden`·404 `not_found`·404 `baseline_not_published`와 저장 시 409 `baseline_conflict`는 BE가 판정한다. 기준 충돌 시 결과를 저장하지 않고 최신 기준으로 다시 구성해야 한다.

## 8. 임시 응답(stub)과 병렬 개발

- `ai/examples/context-packet-132/request.json`은 BE가 보낼 본문(스냅샷+`metadata`), `packet.json`은 AI 응답의 `packet`, `save-packet-args.json`은 BE 저장 입력, `errors.json`은 오류 예시다. 네 파일은 실제 구성 코드로 만들며 `tests/test_context_packet_api.py`가 경로와 대조한다.
- -179 어댑터와 -166 저장 서비스는 AI 연결 전까지 `packet.json`을 임시 응답으로 써서 `SavePacketArgs` 매핑과 재검증을 개발할 수 있다. 임시 응답으로 만든 패킷을 운영 데이터로 저장하지 않는다.

## 9. 담당자 확인 필요·미완료

- BE→AI 호출 방식(내부 토큰·시간 예산·usage 저장)과 `metadata` 조립 위치(`ContextMcpService` 안)는 변준성·전진과 정한다.
- `request` 2,000 코드 포인트 제한과 비밀값 입력 금지 안내의 공용 명세 반영.
- `constraints`를 `POLICY`·`CONSTRAINT` description에서만 만드는 규칙과 `unresolved` 문자열 형식(`KIND: 설명`)의 수용 여부.
- 요청 분석 힌트(`request_summary`)를 BE 검색에 쓸지 여부. 현재는 어떤 검색에도 연결하지 않는다.
- `direct_match_truncation`을 BE `SavePacketArgs`에 구조 필드로 추가할지. 추가 전까지는 `unresolved`의 `SELECTION_TRUNCATED` 문자열로 보존한다.
- 설계 저장 출처 가운데 현재 기준 문서 버전에 속하고 위치가 확인된 것을 `sources`에 포함할지. 현재는 모두 제외하고 `design_items[].source`에만 둔다.
- MCP-01 실제 호출로 응답 확인(-179 연결 후), 응답 잘림·전체 조회 경로(#95).

## 10. 검증

```text
cd ai
python -m pytest tests/test_context_packet.py tests/test_context_packet_api.py -q
python -m ruff check .
python -m ruff format --check .
```
