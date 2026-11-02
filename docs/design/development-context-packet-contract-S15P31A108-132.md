# 개발 컨텍스트 패킷 구성 계약 (#132)

작성: 박상은 · Jira: S15P31A108-132 · 대상: 전진(MCP 어댑터 -179), 변준성(패킷 저장 -166)

**제안 상태다.** MCP-01 `get_development_context`의 패킷 **내용**을 #131 선택 결과와 BE가 제공한 메타데이터로 구성하는 AI 쪽 계약을 적는다. 도구 인수·응답 모양은 [MCP Tools MCP-01](../api/02-mcp-tools.md#mcp-01), BE 서비스 입구는 [서비스 연결 계약 3절](08-mcp-service-contract.md)이 기준이다. 이 문서는 "AI가 무엇을 받아 무엇을 돌려주는가"만 정하며, 권한·현재성 검증·패킷 저장·`content_hash`는 계속 BE가 맡는다. 실제 MCP-01 호출 확인과 BE→AI 호출 방식 합의는 아직 남았다.

## 1. 흐름과 책임

1. BE `ContextMcpService.getDevelopmentContext`가 권한·인수(400/403/404)를 확인하고 현재 기준의 승인 설계·원문 근거·확정 관계·작업 계획 참조를 모아 #131 스냅샷(`SelectionInput`)과 아래 `metadata`를 만든다.
2. AI는 스냅샷으로 선택·의존 확장을 수행하고(#131), `metadata`와 합쳐 패킷 내용을 돌려준다. ID·출처·제약·승인은 만들지 않고 원문도 복사하지 않는다.
3. BE는 돌려받은 `design_item_version_ids`·`sources`·`related_tasks`가 **현재** 기준·계획 리비전과 일치하는지 다시 확인한 뒤 `ContextPacketService.save`로 불변 패킷을 저장하고 `content_hash`를 계산한다. 색인·구성 모듈 미준비는 503 `context_not_ready`로 끝내고 패킷을 저장하지 않는다. 검색을 마친 0건은 정상 응답의 `unresolved`다.

구현: `ai/app/schemas/context_packet.py`(모델), `ai/app/services/retrieval/context_packet.py`(`compose_development_context`, `check_packet_metadata`), HTTP는 기존 `POST /v1/development-context-selections`([#131 초안](../experiments/context-selection-http-S15P31A108-131.md))에 선택적 `metadata`를 더한 형태다. 예시: `ai/examples/context-packet-132/`.

## 2. 입력: 스냅샷 + `metadata`

요청 본문은 #131과 같고 `metadata`가 추가된다. `metadata`가 없으면 응답은 선택 결과만 담는다(기존 동작). `metadata`는 **스냅샷과 정확히 같은 집합**을 설명해야 하며, 불일치는 모델 호출 전에 400 `validation_failed`다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `design_items[]` | DesignItemMeta | Y | 스냅샷 `items`의 모든 버전. `design_item_version_id`·`design_item_id`가 스냅샷과 일치해야 한다 |
| `design_items[].key` | string | Y | 표시 키(예: `API-001`) |
| `design_items[].kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 00-common DesignItemVersion.kind |
| `design_items[].version_no` | integer ≥ 1 | Y | 항목 버전 번호 |
| `design_items[].source` | Source | N | 항목의 원문 출처. 있으면 패킷 `sources`에 합쳐진다 |
| `design_items[].description` | string ≤ 10,000 | N | 승인 본문의 `content.description`. `POLICY`·`CONSTRAINT`만 `constraints`로 쓴다 |
| `evidence[]` | EvidenceMeta | Y | 스냅샷 `evidence`의 모든 후보. `evidence_id`와 `document_version_id`가 일치해야 한다 |
| `evidence[].document_id`, `version_no`, `location` | — | Y | 해당 청크의 00-common Source 값 |
| `task` | TaskMeta | 조건부 | 스냅샷 `task_id`가 있으면 필수, 없으면 금지. `task_id`·현재 `plan_revision_id`·`status` |

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
| `design_items[].source` | Source \| null | `metadata`의 항목 출처 |
| `design_item_version_ids[]` | ID | `design_items`와 같은 순서의 버전 ID. BE `SavePacketArgs.designItemVersionIds` |
| `constraints[]` | string ≤ 500 | 선택된 `POLICY`·`CONSTRAINT` 항목의 `description`을 순서대로. 500 코드 포인트 초과는 뒤를 잘라 `…`를 붙인다 |
| `unresolved[]` | string ≤ 500 | BE 저장용 요약 문자열. 선택 상태는 코드 그대로(`NO_MATCH`, `DEPENDENCY_UNVERIFIED`), 검토 후보는 `"<KIND>: <description>"`을 500 코드 포인트로 자른다 |
| `unresolved_details[]` | UnresolvedDetail | `unresolved`와 같은 순서의 구조형. `kind`(`NO_MATCH\|DEPENDENCY_UNVERIFIED\|POSSIBLE_CONFLICT\|INSUFFICIENT_EVIDENCE`), 전체 `description`(≤ 2,000), `references[]`(`kind`=`design_item\|evidence`, `id`). 인용문은 넣지 않는다 |
| `related_tasks[]` | RelatedTask | 요청의 작업 1건(`metadata.task` 값). 다른 작업 검색은 범위 밖 |
| `sources[]` | Source | 선택된 원문 근거의 출처(선택 순서) 다음에 선택된 설계의 출처(설계 순서). 같은 (document_id, document_version_id, version_no, location)은 한 번만 |
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
| `design_item_version_ids` | `designItemVersionIds` | 모두 현재 기준 설계(400), 기준 없음(404 `baseline_not_published`) |
| `constraints` | `constraints` | — |
| `unresolved` | `unresolved` | — |
| `related_tasks` | `relatedTasks` | 현재 계획 리비전·상태와 일치(400) |
| `sources` | `sources` | 현재 기준 문서 버전·번호와 일치(400) |

`content_hash`는 BE가 `{project_id, task_id, request, baseline_id, content}`의 정규 JSON(키 정렬·목록 순서 유지)으로 계산한다. AI는 해시를 만들지 않으며, 해시 일치만으로 실제 전송 사실을 증명하지 않는다. `design_items`·`unresolved_details`·`request_summary`·`direct_match_truncation`은 저장 입력에 넣지 않는다.

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

BE 쪽 403 `forbidden`·404 `not_found`·404 `baseline_not_published`는 AI 호출 전후에 BE가 판정한다.

## 8. 임시 응답(stub)과 병렬 개발

- `ai/examples/context-packet-132/request.json`은 BE가 보낼 본문(스냅샷+`metadata`), `packet.json`은 AI 응답의 `packet`, `save-packet-args.json`은 BE 저장 입력, `errors.json`은 오류 예시다. 네 파일은 실제 구성 코드로 만들며 `tests/test_context_packet_api.py`가 경로와 대조한다.
- -179 어댑터와 -166 저장 서비스는 AI 연결 전까지 `packet.json`을 임시 응답으로 써서 `SavePacketArgs` 매핑과 재검증을 개발할 수 있다. 임시 응답으로 만든 패킷을 운영 데이터로 저장하지 않는다.

## 9. 담당자 확인 필요·미완료

- BE→AI 호출 방식(내부 토큰·시간 예산·usage 저장)과 `metadata` 조립 위치(`ContextMcpService` 안)는 변준성·전진과 정한다.
- `request` 2,000 코드 포인트 제한과 비밀값 입력 금지 안내의 공용 명세 반영.
- `constraints`를 `POLICY`·`CONSTRAINT` description에서만 만드는 규칙과 `unresolved` 문자열 형식(`KIND: 설명`)의 수용 여부.
- 요청 분석 힌트(`request_summary`)를 BE 검색에 쓸지 여부. 현재는 어떤 검색에도 연결하지 않는다.
- MCP-01 실제 호출로 응답 확인(-179 연결 후), 응답 잘림·전체 조회 경로(#95).

## 10. 검증

```text
cd ai
python -m pytest tests/test_context_packet.py tests/test_context_packet_api.py -q
python -m ruff check .
python -m ruff format --check .
```
