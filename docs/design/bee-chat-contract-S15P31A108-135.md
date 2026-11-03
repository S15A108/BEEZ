# 비비 초기화·설계 대화·근거 응답 계약 초안 (#135)

작성: 박상은 · Jira: S15P31A108-135 · 대상: 오서현(BEE-A01 문서 조회·검색·answer_id·피드백 연결), 전진(PRJ-A01 `ProjectCreatedListener` 연동), 화면 S15P31A108-139 · 선행: [#129 청킹·검색](document-chunking-search-contract-S15P31A108-129.md), [#130 색인 검증](document-index-refresh-contract-S15P31A108-130.md), [#133 질문·답변](planning-question-answer-contract-S15P31A108-133.md), [#134 설계 초안](answer-design-draft-contract-S15P31A108-134.md)

**제안 상태다.** [AI-A05 `/v1/bee/chat`](../api/03-ai-internal.md#ai-a05)의 원문 RAG 확장(scope·embedding_profile_id·retrieved_evidence·citations)을 구현 가능한 수준으로 구체화하고, PRJ-A01 처리 규칙 "비비의 프로젝트별 논리 인스턴스를 초기화한다"를 위한 `POST /v1/bee/instances`를 더한 AI 쪽 계약을 적는다. 권한 검사·의미 검색·answer_id·피드백·대화 보존은 BE가 맡고 AI는 입력을 바꾸지도 저장하지도 않는다. 03-ai-internal.md의 AI-A05 갱신은 오서현 확인 뒤 반영한다(11절). 2026-11-03 PR #96 리뷰 세 차례로 인용·사실 검증, 검토 범위, 관련 작업 근거, 담당자·이력·계획 전달, 프롬프트 구성을 고쳤다(5절·11절).

## 1. 흐름과 책임

Jira 작업 내용 2 "질문 → 의미 검색 → 근거 선택 → 응답 생성"은 다음과 같이 나뉜다.

| 단계 | 담당 | 내용 |
| --- | --- | --- |
| 질문 | BE (BEE-A01) | 세션·멤버십 검사. 질문 ≤ 2,000자. `scope`(`APPROVED`: 현재 `current_baseline_id`의 문서 버전, `DOCUMENT_REVIEW`: 접근 검사를 마친 명시 버전) 확정 |
| 의미 검색 | BE | AI-A04(`purpose=query`)로 질문을 임베딩하고 허용 집합·READY·활성 프로필 안에서 SQL top-k를 구한다(#129·#130). 결과가 `retrieved_evidence`다. 미색인은 409 `index_not_ready`, 정상 0건은 `[]` |
| 근거 선택 | AI | BE 순서를 유지하고 `max_evidence`(기본 12)까지만 모델에 보인다. 넘친 수는 `evidence_omitted`·`notices`에 남긴다 |
| 응답 생성 | AI | 고정 시스템 프롬프트 + 데이터(프로젝트 프로필·근거·승인 항목·관련 작업·이력)로 모델 호출 1회(`feature=BEE_CHAT`) |
| 검증 | AI | 인용은 근거 원문의 정확한 부분 문자열, 본문의 사실 토큰은 인용·참조 안에 있어야 하며 아니면 출력 전체 거부. 항목·작업 참조는 입력에 있는 것만. 근거 없는 답은 고정 문구로 보류(5절) |
| 반환 직전 | BE | 현재 권한·baseline 재검사. 바뀌었으면 이번 답변 폐기(409 `baseline_changed`). `answer_id` 발급, `citations`를 화면 BeeCitation으로 변환, `grounded` 전달 |

구현: `ai/app/schemas/bee_chat.py`(모델), `ai/app/services/bee_chat/`(인스턴스·제안·검증, [README](../../ai/app/services/bee_chat/README.md)), `ai/app/routers/bee_chat.py`(기본 비활성 경로). 예시: `docs/api/examples/bee-chat/`. 검토 자료: `ai/evaluations/bee_chat/`.

## 2. 비비 인스턴스 초기화: `POST /v1/bee/instances`

`INTERNAL_SERVICE` Bearer 인증, `Content-Type: application/json`, `X-Request-Id` 선택. `BEE_CHAT_ENABLED=true`가 필요하고 `GMS_KEY`는 필요 없다(모델 호출 없음).

| 요청 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id`, `request_key` | ID, string ≤ 256 | Y | 프로젝트와 호출 식별값 |
| `project.name` | string 1~40 | Y | PRJ-A01 이름 |
| `project.description` | string 0~300 \| null | N | PRJ-A01 소개 |

응답 `{"result": {"instance": BeeInstance}, "origin": "TEMPLATE", "usage": []}`:

| 필드 | 설명 |
| --- | --- |
| `project_id`, `profile_version` | 요청 프로젝트, 프롬프트 규칙 버전(`bee-v1`) |
| `project` | 요청의 이름·소개 그대로. BE가 저장해 두었다가 질문마다 `instance.project`로 되돌려 준다 |
| `persona`, `greeting`, `scope_statement` | "비비", 첫 인사("공용 문서의 승인된 내용에서 찾아 답해 드릴게요."), 범위 안내. 화면 첫 말풍선·설명 문구로 쓸 수 있다 |
| `system_prompt`, `system_prompt_hash` | **고정 규칙만 담은** 시스템 프롬프트 전문과 SHA-256. 사용자가 쓰는 프로젝트 이름·소개는 지시문에 넣지 않고 질문 때 데이터(`project` 필드)로 모델에 보낸다. 따라서 프롬프트와 해시는 모든 프로젝트에서 같고 `profile_version`의 실제 내용을 식별한다 |

**`ProjectCreatedListener` 연동 제안(전진·오서현 확인):** 현재 리스너(`backend/.../project/ProjectCreatedListener.java`)는 AFTER_COMMIT에 로그만 남긴다. 여기서 `AiClient`로 `/v1/bee/instances`를 호출해 `profile_version`·`system_prompt_hash`·`project`(필요하면 `greeting`·`scope_statement`)를 프로젝트에 저장하는 것을 제안한다. AI 실패·503(비활성)은 로그만 남기고 프로젝트 생성은 되돌리지 않는다(이벤트 주석과 같음). 저장이 없거나 실패해도 BEE-A01은 `instance`를 생략하면 그대로 동작하므로 질문이 막히지 않는다. 응답 `result.instance.system_prompt_hash`로 저장된 값과 실제 프롬프트 버전이 같은지 BE가 대조할 수 있다. 테이블·컬럼은 BE가 정한다(저장하지 않고 질문마다 이름·소개만 보내도 된다).

## 3. 질문: `POST /v1/bee/chat`

`INTERNAL_SERVICE` Bearer 인증, `Content-Type: application/json`, `X-Request-Id` 선택. `BEE_CHAT_ENABLED=true`와 `GMS_KEY`가 필요하다. 본문 상한 `BEE_CHAT_MAX_BODY_BYTES`(기본 2 MiB).

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id`, `request_key` | ID, string ≤ 256 | Y | #133·#134와 같음. 실제 모델 호출 key는 `llm-bee-chat-<uuid>` |
| `question` | string 1~2,000 | Y | 질문(화면 입력 상한과 같음) |
| `instance` | `{profile_version: "bee-v1", project: {name, description?}}` | N | 2절에서 저장한 인스턴스. 이름·소개는 모델에 데이터로 전달된다. 없으면 프로젝트 정보 없이 동작 |
| `scope` | `{mode: APPROVED\|DOCUMENT_REVIEW, document_version_ids: ID[], baseline_id?}` | Y | 00-common RetrievalScope. `APPROVED`는 `baseline_id` 필수, `DOCUMENT_REVIEW`는 금지 |
| `embedding_profile_id` | string | Y | 검색에 쓴 활성 프로필 |
| `retrieved_evidence[]` | RetrievedEvidence | N | BE 검색 결과(순위 순). 최대 50. `evidence_id` 유일, `(document_version_id, chunk_key)` 유일, 모든 `document_version_id`는 `scope` 안, `embedding_profile_id`는 요청 값과 같아야 한다(어기면 422). 정상 0건은 `[]` |
| `approved_items[]` | ApprovedDesignItem | N | 00-common DesignItemVersion 중 `status=APPROVED`만. 최대 500. `id`·`design_item_id`·`key` 유일. 종류별 필수 필드는 #134와 같은 검사. `source`는 BE가 보존한 불변 출처라 현재 `scope`보다 **오래된 문서 버전일 수 있으며 그대로 받는다**(APPROVED). **`DOCUMENT_REVIEW`에서는 빈 배열이어야 한다**(어기면 422): 검토 범위는 명시 문서 버전만 보고 승인 설계를 섞지 않는다 |
| `related_tasks[]` | TaskDetail | N | 00-common TaskDetail **그대로** 보내도 된다. AI는 `id`·`key`·`status`·`assignee_member_id`·`current_plan_revision.plan`의 `title`·`goal`·`design_refs`를 읽어 모델에 보이고 나머지는 무시한다. 계획 `title`·`goal`은 BE처럼 길이를 제한하지 않는다(본문 2 MiB 상한만). 모델에는 앞 2,000자만 보인다. 최대 100 |
| `unresolved[]` | string ≤ 2,000 | N | BE가 이미 아는 미확정. 모델에 보이고 응답 앞머리에 그대로 돌아온다 |
| `history[]` | `{role: USER\|BEE, text}` | N | 열린 창의 이전 문답. 최대 10턴. USER 턴은 질문 상한(2,000자), BEE 턴은 답변 상한(10,000자)까지 받으므로 이 경로가 돌려준 답변은 그대로 이력이 된다. 모델에는 턴당 앞 2,000자만 보이고 잘린 턴은 `…`로 표시한다(8절). 데이터로만 취급 |
| `max_evidence` | integer 1~50 | N | 모델에 보일 근거 수. 기본 12 |

RetrievedEvidence: 00-common과 같이 `evidence_id`, `document_id`, `document_version_id`, `version_no`, `chunk_key`, `text`(≤ 20,000자), `locator`(`text_lines` 행 범위), `embedding_profile_id`. 화면 표시용 `title`(문서 제목)·`section_title`(절 제목)은 선택이며 그대로 `citations`에 실린다.

## 4. 응답

성공 본문은 `{"result", "origin": "MODEL", "model", "usage"}`이며 `usage`는 실제 호출별 Usage(`feature=BEE_CHAT`)다. 정보 부족·범위 밖도 **HTTP 200**이고 `answer_status`로 구분한다.

`result`(00-common ChatResult `text`·`sources`·`unresolved`·`next_actions`·`citations`의 상위 집합):

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `answer_status` | `ANSWERED\|INSUFFICIENT_EVIDENCE\|OUT_OF_SCOPE` | 근거 있는 답 / 근거 부족(정보 부족) / 프로젝트 밖 질문 |
| `grounded` | boolean | 확인된 인용, 승인 항목 참조, 관련 작업 참조 중 하나 이상. `ANSWERED`일 때만 참. BE가 BEE-A01 `grounded`로 전달 |
| `text` | string ≤ 10,000 | 답변. `ANSWERED`면 모델 본문(한국어)에 `[n]` 표식, 아니면 고정 문구: 정보 부족 "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.", 범위 밖 "이 질문은 이 프로젝트의 문서·설계 범위 밖이라 답하지 않습니다. 프로젝트 문서나 승인 설계에 대해 물어봐 주세요." |
| `citations[]` | BeeCitation | `n`(본문 `[n]`과 1:1, 1..k 순서), `evidence_id`, `document_id`, `document_version_id`, `version_no`, `chunk_key`, `locator`, `quote`(근거 원문의 정확한 부분 문자열, ≤ 1,000자), `title?`, `section_title?`, `claim?`. 최대 20. 본문 `[n]`이 가리키는 인용만 남고 같은 인용을 거듭 쓰면 한 번호로 합친다 |
| `sources[]` | 00-common Source | 인용의 출처(위치 = `절 제목 / L시작-L끝`)와 **현재 `scope` 안의 문서 버전을 가리키는** 참조 승인 항목의 저장 출처, 중복 없이. 범위 밖(이전 문서 버전) 출처는 `design_refs[].source`에만 두고 여기에 넣지 않는다(#132 패킷과 같은 원칙). 항상 `scope` 안이므로 BEE-A01 `sources` 그대로 쓸 수 있다 |
| `design_refs[]` | `{design_item_id, design_item_version_id, key, kind, version_no, source?}` | 답이 기댄 승인 설계 항목(승인 버전 ID와 저장 출처 포함). 최대 20 |
| `task_refs[]` | `{task_id, key, status}` | 답이 기댄 관련 작업. 작업 상태·담당자처럼 작업만으로 답한 경우 이것만으로 `grounded`다. 최대 20 |
| `unresolved[]` | string ≤ 2,000 | 요청의 `unresolved` → 모델이 적은 미확정 → 보류 사유 순, 중복 제거. 최대 50 |
| `next_actions[]` | string ≤ 2,000 | 권장 행동. 최대 10 |
| `notices[]` | string | 화면 안내: `DOCUMENT_REVIEW`면 "미승인 문서 검토 범위의 답변입니다…", 근거 상한으로 뺀 건수 |
| `scope`, `embedding_profile_id` | — | 요청 값. BE 최종 검사용 |
| `evidence_considered`, `evidence_omitted` | integer | 모델에 보인 근거 수, 상한으로 뺀 수 |
| `instance` | `{profile_version, system_prompt_hash}` \| null | 이 답에 쓴 프롬프트 버전. 요청에 `instance`가 없으면 null |

본문의 `[n]` 표식은 AI가 넣는다(AI-A05 처리 규칙의 미정 사항을 이렇게 정한다). 본문의 모든 `[n]`은 `citations`에 있고, 없는 번호를 쓴 출력은 거부된다.

## 5. 검증 규칙

- 다음은 모델이 입력과 모순된 것이므로 **출력 전체를 거부**한다(502 `model_unavailable`, usage는 실패로 기록, BE는 새 request key로 재시도하거나 템플릿 폴백): `citations[].evidence_id`가 모델에 보인 근거에 없음(상한으로 뺀 근거 포함), `design_refs`가 `approved_items[].id`에 없음, `task_refs`가 `related_tasks[].id`에 없음, **`quote`가 해당 근거 `text`의 정확한 부분 문자열이 아님**(공백만 다듬음), 본문의 `[n]`이 1부터 인용 수까지의 범위 밖(`[0]` 포함), `ANSWERED`인데 본문이 비어 있음, **본문의 사실 토큰이 근거 안에 없음**(아래). 원문에 없는 인용을 그 인용만 버리고 본문을 남기면 인용이 뒷받침하던 주장이 그대로 노출되므로(PR #96 P1), 다른 인용이 맞더라도 답변을 보이지 않는다.
- **사실 토큰 검사(문장 단위).** 본문을 문장으로 나누고(마침표·물음표·느낌표 뒤의 공백이나 줄바꿈에서; 문장 끝 뒤에 온 `[n]`은 앞 문장에 붙는다), 각 문장의 확인 가능한 사실 토큰 — 숫자(`30`, `413`, `1.5`; 천 단위 쉼표는 무시해 `1,000`과 `1000`은 같다), `TASK-001`류 표시 키, `/`로 시작하는 경로(끝 문장 부호 제외), `documents.owner_id`·`document_version_id`·`documentVersionId`류 식별자, 3자 이상 대문자 코드(`RESTRICT`, `POST`, `HTTP`) — 가 **그 문장의 `[n]`이 가리키는 인용문**(과 그 근거의 문서·절 제목) 또는 **참조한 승인 항목(키·종류·본문)·참조한 작업(키·상태·담당자·계획)** 에서 같은 규칙으로 뽑은 토큰 집합에 독립된 토큰으로 있어야 한다(부분 문자열 비교가 아니므로 150 MB 원문에 50 MB 답은 거부). 인용문이 속한 청크의 다른 문장은 근거가 아니다: "기본 한도 30 MB. 프리미엄 한도 50 MB" 청크에서 기본 문장만 인용하고 "기본 한도는 50 MB"라고 쓰면 거부된다. 하나라도 없으면 거부한다. 질문 문장은 근거로 치지 않으므로 질문의 "50 MB"를 되풀이해도 통과하지 않는다. 한계: 부정·조건 뒤집기처럼 토큰이 같은 의미 모순은 잡지 못한다(11절의 판정 모델 후속안). 정당한 답이 새 숫자(예: "2가지")를 쓰면 거부될 수 있으므로 실제 모델 평가로 거부율을 잰다.