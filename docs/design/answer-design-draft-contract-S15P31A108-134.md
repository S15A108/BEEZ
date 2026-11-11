# 답변 반영 설계 초안 계약 초안 (#134)

작성: 박상은 · Jira: S15P31A108-134 · 대상: 오서현(PLN-A05 답변 반영 설계 초안 생성, 저장·승인·발행), 화면 S15P31A108-202 · 선행: [#133 질문·답변 계약](planning-question-answer-contract-S15P31A108-133.md)

**제안 상태다.** 권한을 확인한 문서 고정 버전과 #133 질문·답변, BE가 보유한 설계 항목 버전을 입력으로 받아 **미승인 설계 항목 초안(DRAFT)**, 항목별 출처(원문 인용·답변 참조), **답변 전후 차이를 보여 주는 변경 목록**을 돌려주는 AI 쪽 계약을 적는다. 저장·ID 발급·승인·발행은 BE가 맡고 AI는 입력을 바꾸지 않는다. 호출 범위·입력 범위·후보 보관·채택 검증은 BE(오서현) 제안을 1절·6절에 반영했고, 화면 202와 후보 표 설계는 합의가 남았다(9절).

## 1. 흐름과 책임

1. BE가 PLN-A05 요청을 **비동기 작업**으로 접수하면 문서 버전마다 AI를 한 번씩 호출한다. 범위는 **최초 baseline 발행 전 초안 생성**이며, 발행 후 설계 변경은 기존 변경 제안·승인 경로로 간다. 요청에는 권한을 확인한 **문서 고정 버전·원문**, 그 버전의 `planning_question` 행(질문·근거·답변 상태·답변), 그리고 **그 문서에서 나온 현재 설계 항목**을 설계 항목당 **한 버전만**(사람이 고친 현재 DRAFT가 있으면 그 본문, 없으면 현재 APPROVED) 담는다. 출처 없는 수동 작성 항목은 넣지 않고, 이전 문서 버전에서 이어받는 항목은 BE가 명시적으로 고른다. BE는 접수 시 문서 버전·질문·답변·기존 항목 본문을 스냅샷으로 보존하고, 문서별 성공·실패를 기록해 실패한 문서만 재시도한다. #128 구조화 추출이 없으면 기존 항목은 빈 목록이어도 된다.
2. AI는 원문을 #127 파서로 블록·행 범위로 만들고, 모델에 블록 원문·기존 항목·답변된 질문을 넘겨 초안 제안을 받은 뒤, 모든 인용을 블록 원문과 대조하고 기준 항목 ID·답변 키를 입력과 대조한다. 각 초안에 기준 항목의 **before**와 초안의 **after**, 바뀐 필드, 충돌 필드, 답변 참조를 붙인다.
3. BE는 응답을 바로 DRAFT로 만들지 않고 **생성 후보**로 보관한다(항목별 before/after·근거·채택/폐기 상태, 결과 전체의 `unresolved`·사용량). 사용자가 화면 202에서 채택할 때만 실제 DRAFT에 반영한다(`origin=AI_PROPOSED`). CREATE는 새 항목과 v1 DRAFT를 만들고, 기존 DRAFT의 UPDATE는 그 DRAFT를 갱신하며, APPROVED 기준은 그대로 두고 새 DRAFT 버전을 만든다. 승인·발행은 PLN-A10 등 기존 경로다. 세부는 6절.

구현: `ai/app/schemas/design_drafts.py`(모델), `ai/app/services/design_drafts/`(모델 제안·검증·변경 목록, [README](../../ai/app/services/design_drafts/README.md)), `ai/app/routers/design_drafts.py`(기본 비활성 경로). 예시: `docs/api/examples/design-drafts/`. 검토 자료: `ai/evaluations/design_drafts/`.

## 2. 요청: `POST /v1/design-drafts`

`INTERNAL_SERVICE` Bearer 인증, `Content-Type: application/json`, `X-Request-Id` 선택. 기본 비활성이며 `DESIGN_DRAFTS_ENABLED=true`와 `GMS_KEY`가 필요하다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id`, `request_key` | ID, string ≤ 256 | Y | #133과 같음. 실제 모델 호출 key는 `llm-design-drafts-<uuid>` |
| `document` | `{document_id, document_version_id, version_no}` | Y | 분석할 고정 버전 |
| `kind`, `media_type`, `content`, `content_hash`, `text_hash`, `normalization_version`, `parser_profile` | — | Y | #133 요청과 같음(#127 파서 계약) |
| `existing_items[]` | ExistingDesignItem | N | 문서에서 나온 현재 설계 항목. 최대 500. `design_item_version_id`·`design_item_id`·`key`는 서로 달라야 한다(**설계 항목당 한 버전**, 어기면 422) |
| `questions[]` | AnsweredQuestion | N | 저장된 #133 질문. 최대 200, `question_key` 중복 금지 |
| `max_items` | integer 1~100 | N | 초안 수 상한. 기본 50 |

ExistingDesignItem: `design_item_version_id`, `design_item_id`, `key`(표시 키), `kind`(5종), `version_no`, `status`(`DRAFT|APPROVED`), `schema_version`(1), `content`(DesignItemContent v1), `source?`(00-common Source). 종류별 필수 필드(API `method`·`path`, DATA `entity`·`fields`)가 없으면 400이다. 검사 규칙은 BE `DesignContentValidator`와 같다(`description` ≤ 10,000자, API `method`는 대문자 HTTP 메서드, `path`는 `/`로 시작하고 공백·`?`·`#`·제어 문자 없이 2,000자 이하, DATA `entity`는 비어 있지 않은 문자열로 길이 제한 없음, `fields`는 배열). BE가 저장한 본문은 이 요청에서 거부되지 않아야 하므로 BE보다 좁은 제한은 두지 않는다. 본문은 값이 `null`인 필드까지 입력 그대로 보존한다. BE `DesignJson`이 거부하는 것(모든 깊이의 빈 필드명, 32단계를 넘는 중첩, JSON이 아닌 값, 정렬·압축 JSON 1 MiB 초과)은 여기서도 거부한다(`check_json_content`).

AnsweredQuestion: `question`(#133 `SupplementQuestion` 그대로), `evidence[]`(#133 `EvidenceItem`, 이 문서 버전 소속), `status`(`OPEN|ANSWERED|DISMISSED`), `answer`(#133 `QuestionAnswer`, `ANSWERED`일 때만). 답변은 질문의 `answer_format`에 맞아야 하며(`validate_answer`) 어긋나면 422 `invalid_ai_input`이다. 모델에는 `ANSWERED` 질문만 본문이 전달되고 나머지는 개수만 전달된다.

## 3. 응답

성공 본문은 `{"result", "origin": "MODEL", "model", "usage"}`이며 `usage`는 실제 호출별 Usage(`feature=BEE_DRAFT`)다. 모델 없는 경로는 없다.

`result`:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `document`, `kind`, `text_hash`, `parser_profile`, `max_items` | — | 요청 값. BE는 저장 전 같은 버전인지 대조한다 |
| `items[]` | DraftItem | 초안. 근거 등급 순(DOCUMENT_AND_ANSWER → ANSWER → DOCUMENT → NONE), 같은 등급 안에서는 모델 순서 |
| `changes[]` | DraftChange | `items`와 같은 순서·개수. 답변 전후 차이 |
| `untouched_existing_ids[]` | ID | 어떤 초안도 건드리지 않은 기존 항목 버전. BE는 그대로 둔다 |
| `unanswered_question_keys[]` | string | `OPEN`·`DISMISSED` 질문 키 |
| `unresolved[]` | string ≤ 2,000 | 문서와 답변으로 정하지 못한 점(모델 서술, 최대 50) |
| `omitted_items` | integer | `max_items` 상한으로 버린 초안 수 |

DraftItem:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `candidate_key` | string | `draft-<해시 12자리>`: 종류·기준 버전 ID·본문의 해시. **한 결과 안에서** 항목과 변경을 짝짓는 임시 키이며 최종 ID가 아니다. `before`·답변·근거는 반영하지 않으므로 그것들이 달라도 같을 수 있다 |
| `kind` | 5종 | 기준 항목이 있으면 그 종류와 같다 |
| `status` | `DRAFT` | 유일한 값. 스키마에 다른 상태가 없다 |
| `schema_version` | 1 | DesignItemContent v1 |
| `content` | DesignItemContent | `description` 필수. 종류가 요구하는 필드만 타입 검사(API `method`·`path`, DATA `entity`·`fields`)하고 그 밖의 JSON 필드는 그대로 보존한다(BE 계약과 동일; POLICY의 `method: "manual"` 같은 값도 허용). UPDATE면 기준 항목 본문에 모델이 돌려준 필드를 4절 규칙으로 합친 결과이며, 기준 항목의 `null` 값 필드도 모델이 건드리지 않는 한 그대로 남는다 |
| `base_design_item_version_id`, `base_status` | ID \| null, `DRAFT\|APPROVED` \| null | 갱신 대상 기존 버전과 그 상태. APPROVED 기준은 편집되지 않고 새 DRAFT 버전의 바탕일 뿐이다 |
| `sources[]` | EvidenceItem | **확인된 원문 인용만**(블록 ID·행 범위·위치·인용). 모델 제안당 최대 5이며 합친 초안은 그 합(최대 500)까지 모두 남긴다. 블록에 없는 인용은 버린다 |
| `answer_refs[]` | string | 이 초안이 기댄 답변의 `question_key`. 입력의 `ANSWERED` 질문만 허용. 모델 제안당 최대 10이며 합친 초안은 답변된 질문 수(최대 200)까지 모두 남긴다 |
| `support` | `DOCUMENT_AND_ANSWER\|ANSWER\|DOCUMENT\|NONE` | 출처 등급. `NONE`은 검토자가 대조할 근거가 없는 항목 |
| `rationale` | string ≤ 2,000 | 문서·답변이 무엇을 확정했는지(한국어) |

DraftChange:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `candidate_key` | string | 초안 키 |
| `change` | `CREATE\|UPDATE` | 기준 항목이 있으면 UPDATE |
| `base_design_item_version_id` | ID \| null | UPDATE의 기준 버전 |
| `before` | DesignItemContent \| null | 기준 항목의 **현재 본문 그대로**(답변 전). 입력과 같다 |
| `after` | DesignItemContent | 초안 본문(답변 후). `items[].content`와 같다 |
| `changed_fields[]` | `{field, change, before, after}` | 최상위 본문 필드 중 전후가 다른 것. `change`는 `ADDED`(전에 없던 필드) · `REMOVED`(후에 없는 필드) · `CHANGED`(JSON 값이 다름, `null`에서 값으로 바뀐 것 포함). BE가 필드 없음과 `null`을 다른 JSON으로 보존하므로 둘을 구분하며, `null`이던 필드의 삭제도 `REMOVED`로 실린다. `before`·`after`의 `null`은 `ADDED`·`REMOVED`면 필드 없음, `CHANGED`면 `null` 값이다. 비교는 JSON 직렬화 기준이라 `true`와 `1`도 다른 값이다. UPDATE는 1개 이상이되 충돌만 있으면 비어 있을 수 있다(아래 행). 중첩 객체의 일부만 바뀌어도 그 최상위 필드 전체가 전후 값으로 실린다 |
| `conflicting_fields[]` | string | 같은 기준 항목에 대한 모델 제안들이 **서로 다른 값**을 준 최상위 필드(이름순). `after`에는 마지막 제안의 값이 들어 있으므로 검토자가 이 필드를 확정해야 한다. 제안들이 결국 기존 값으로 되돌아와 `changed_fields`가 비어도 충돌이 있으면 초안을 남긴다(`after`가 `before`와 같음). CREATE는 항상 빈 배열 |
| `answer_refs[]` | string | 초안과 같음. 어떤 답변이 변경을 이끌었는지 |

## 4. 검증 규칙

- 인용(`sources`)은 `block_id`가 문서에 있고 `quote`가 그 블록 원문의 부분 문자열일 때만 남긴다. 아닌 인용은 조용히 버리고 `support`를 다시 계산한다(#133과 같은 기준).
- 검증은 **초안 단위**다(S15P31A108-388). 기준 항목 ID가 입력에 없거나, 합친 본문이 종류별 필수 필드나 BE의 JSON 규칙(중첩 빈 필드명·깊이·크기·값 타입)을 어기는 초안은 **그 초안만 버린다**. 구조가 깨진 모델 항목(객체가 아님, 필수 필드 누락·형식 위반)도 그 항목만 버리고, 항목에 붙은 모르는 필드는 지운다. 빈 인용문(`quote=""`)·형식이 틀린 인용은 그 출처만 빼고, `ANSWERED` 질문 키가 아닌 답변 참조는 그 참조만 뺀다. DATA_CONTRACT의 `fields`를 필드 이름을 키로 한 객체로 내면 내용을 잃지 않게 `[{"name": 키, ...값}]`(값이 문자열이면 `description`) 배열로 바꿔 받는다. 키가 필드 식별자이므로 값 안의 `name`(예: "회원 ID" 같은 표시 이름)은 키를 덮지 않고, 키와 다르면 `label`이 비어 있을 때 `label`로 남긴다 — 실제 ERD에서 데이터 계약이 모두 이 모양으로 와서 전부 버려졌다. 이 변환은 **새 항목에만** 한다. 갱신의 `fields`는 배열을 통째로 바꾸므로, 바뀐 필드만 담은 객체를 배열로 바꾸면 기준 항목의 나머지 필드가 사라지기 때문이다(그런 갱신은 그 초안만 거절). 항목에서 지운 모르는 필드의 이름(값은 남기지 않음)과 항목 상한(100)을 넘겨 잘라 낸 수도 AI 로그에 남긴다. `unresolved`의 2,000자 초과 줄은 2,000자로 자르고 빈 줄·문자열이 아닌 값은 버린다. 버린 초안·항목 수는 AI 로그에만 남긴다(본문은 남기지 않는다). 다만 모델이 항목을 냈는데 **하나도 쓸 수 없으면** 출력 전체를 거부하고(502 `model_unavailable`, usage는 실패로 기록) BE가 다시 시도하게 한다. 응답 최상위가 객체가 아니거나 `items`·`unresolved` 밖의 키(예: `approved`)가 있어도 전체를 거부한다. 실제 문서(3,000~7,000자)에서 한 항목의 빈 인용문·필드 누락 때문에 수십 개 초안이 통째로 버려지는 일이 8번 중 5번 있었다.
- 갱신(UPDATE)의 `after`는 기준 항목 본문에 모델이 돌려준 내용을 **모든 깊이에서 키 단위로 합친** 결과다(`apply_patch`). 모델이 **생략한 키는 기존 값을 보존**하고(답변과 무관한 `parameters`·`responses` 등이 사라지지 않음), **객체 값은 그 안의 키 단위로 다시 합치며**(`responses`에 `400`만 돌려줘도 `200`은 남음), **배열·스칼라 값은 통째로 교체**하고, **JSON `null`은 그 키를 삭제**한다는 뜻이다(어느 깊이에서나). 배열 안의 `null`은 값이다. 이 삭제 규칙은 **모델 패치에만** 적용되며 기존 본문의 `null` 값 필드는 입력 그대로 둔다. 그 필드를 삭제하면 `REMOVED` 변경이다(BE는 필드 없음과 `null`을 다른 JSON으로 본다). 생략과 삭제를 이렇게 구분하므로 `changed_fields`에는 실제로 바뀐 필드만 남는다. 신규(CREATE)의 `null` 키는 버린다.
- 같은 기준 항목을 가리키는 제안이 여럿이면(예: 경로 변경과 응답 변경을 따로 돌려준 경우) **등장 순서대로 하나의 초안으로 합친다**. 본문은 위 규칙으로 차례로 적용하고, 확인된 인용과 답변 참조는 중복 없이 **모두** 남기며(상한은 제안 수에 비례하는 자연 상한뿐), `rationale`은 이어 붙인다. 두 제안이 **같은 경로에 다른 값**을 주거나 한쪽이 통째로 바꾼 값(스칼라·배열·`null`·빈 객체 `{}`) 안의 키를 다른 쪽이 정하면 충돌이다. 빈 객체는 값이므로 스칼라를 `{}`로 바꾸는 것도 충돌이지만, 빈 객체와 그 안의 키 설정은 서로 충돌하지 않는다. 뒤의 제안이 이기되 그 최상위 필드를 `conflicting_fields`에 적어 검토자가 확정하게 한다. 종류가 기준 항목과 다른 제안은 그 제안만 버리고 나머지를 합친다(모두 다르면 그 초안을 버린다). 따라서 기준 항목당 초안은 하나다.
- 기존 항목과 본문이 같은 "갱신"은 변경이 아니므로 버리고 그 항목을 `untouched_existing_ids`에 둔다. 단, 제안들이 충돌한 끝에 기존 값으로 돌아온 경우는 `conflicting_fields`만 있는 초안으로 남긴다.
- 종류·기준·본문이 같은 신규 초안이 둘이면 첫 번째만 남긴다.
- 승인본 불변: 입력의 APPROVED 항목 본문은 `before`에 그대로 나오고, 출력 어디에도 `APPROVED` 상태의 항목은 없다. 테스트 `test_update_of_an_approved_item_keeps_the_approved_content_as_before`와 평가 검사 `approved_bases_kept_as_before`가 이를 확인한다.

## 5. 상한

| 항목 | 값 |
| --- | --- |
| 초안 수 | `max_items`(기본 50, 1~100). 초과분은 근거 등급이 낮은 순으로 버리고 `omitted_items`에 수를 남긴다 |
| 기존 항목 / 질문 | 500 / 200 |
| 모델 제안당 인용 / 답변 참조 | 5 / 10. 합친 초안은 500 / 200까지(확인된 것은 버리지 않음) |
| `unresolved` | 50건, 각 2,000자 |
| 모델 입력 | 블록·기존 항목·답변 JSON 256 KiB 초과 시 422 `invalid_ai_input` |
| 본문 | `DESIGN_DRAFTS_MAX_BODY_BYTES` 기본 2 MiB |

## 6. 저장과 채택 (오서현 제안 반영, 표 설계는 합의 중)

BE 제안대로 AI 응답은 바로 DRAFT가 되지 않고 **생성 작업 → 생성 후보 → 실제 DRAFT**로 나뉜다. 각 단계가 AI 응답에서 가져가는 것은 다음과 같다.

| 단계 | 보존·사용할 AI 응답 | 비고 |
| --- | --- | --- |
| 생성 작업 | `document`·`text_hash`·`parser_profile`(대상 문서 확인), `usage[]`(실제 호출 사용량), 처리 상태 | 입력 스냅샷(문서 버전·질문·답변·기존 항목 본문)은 BE가 접수 시 보존한다 |
| 생성 후보(항목별) | `items[]`와 `changes[]`: `candidate_key`, `kind`, `content`(= `after`, BE 용어로 `proposed_after`), `base_design_item_version_id`·`base_status`, **`before`(생성 당시 기준 본문 그대로)**, `changed_fields`, `conflicting_fields`, `sources[]`, `answer_refs[]`, `support`, `rationale` | 모두 **불변으로 보존**한다. 기존 DRAFT는 같은 행이 수정될 수 있어 `before`를 나중에 재구성할 수 없기 때문이다. 기준 본문 해시는 `before`에 BE `DesignJson.hash`를 적용해 얻는다(`before`는 입력 본문과 동일하며 `null` 필드도 보존된다). 생성에 쓴 답변은 BE 스냅샷에서 `answer_refs`의 질문 키로 찾는다 |
| 생성 후보(결과 전체) | `unresolved[]`, `untouched_existing_ids[]`, `unanswered_question_keys[]`, `omitted_items` | `untouched_existing_ids`는 "이번 결과가 건드리지 않음"이지 삭제 지시가 아니다. 재생성 결과에 없는 기존 항목은 삭제하지 않는다 |
| 실제 DRAFT(채택 시) | `content`를 `design_item_version`에 저장: `status=DRAFT`, `origin=AI_PROPOSED`, `schema_version=1`, `title=content.description`, `source_document_version_id`=요청 문서 버전, `source_locator`=`sources[0].locator`(대표 출처, 전체 근거를 대체하지 않음) | CREATE는 새 `design_item`(키 발급)과 v1 DRAFT, UPDATE는 기준 DRAFT 갱신 또는 APPROVED 기준의 새 DRAFT 버전. AI 제안과 사용자 편집 결과는 구분해 둔다 |

채택 규칙(BE 제안): 모든 후보는 사용자 채택 후 반영하고, `support=NONE`은 "확인 가능한 근거 없음"을 확인한 뒤 명시적으로 채택한다. `conflicting_fields`가 있는 후보는 사용자가 그 필드를 확정하기 전에는 채택할 수 없게 하는 것을 제안한다. 반영 직전에 대상 문서·답변이 생성 당시와 같은지, UPDATE 대상 DRAFT의 버전·본문 해시가 같은지, baseline이 그사이 발행되지 않았는지 확인하고, 다르면 반영을 막고 재생성을 안내한다. 같은 후보의 채택 요청 재전송은 **BE가 발급한 후보 ID**를 기준으로 중복 반영하지 않는다. 후보를 폐기해도 실제 DRAFT에는 영향이 없다.

`candidate_key`는 한 생성 결과 안에서 `items[]`와 `changes[]`를 짝짓는 키일 뿐이다. 종류·기준 버전 ID·본문의 해시라서 `before`·답변·근거가 달라도 같을 수 있으므로, **생성 결과 간 중복 제거나 채택 멱등의 단독 기준으로 쓰지 않는다.** 후보는 생성 작업별로 보존하고 채택 멱등은 BE 후보 ID로 처리한다. 한 결과 안에서 기준 항목당 후보는 하나이며, 여러 생성 결과에 같은 항목의 후보가 있으면 BE 제안대로 하나를 골라 반영하고 이후는 기존 편집 기능으로 고친다.

## 7. 오류

형식은 `{"error": {"code", "message"}}`이며 모델 호출 뒤 실패면 `usage`를 덧붙인다. 예시: `docs/api/examples/design-drafts/errors.json`.

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `invalid_internal_token` | 내부 토큰 누락·불일치 |
| 503 | `design_drafts_not_configured` | 기본 비활성 또는 한도 설정 오류 |
| 400 | `validation_failed` | JSON·필드·허용 값 오류 |
| 422 | `invalid_ai_input` | 중복 항목(버전·설계 항목·키)·질문, 답변 형식 불일치, 파서 거부(`details.reason`), 모델 입력 256 KiB 초과 |
| 413 / 415 | `request_too_large` / `unsupported_media_type` | 본문 크기 / 미디어 타입 |
| 502 | `model_unavailable` | 모델 호출 실패, 응답 최상위 구조 위반, 낸 항목 중 쓸 수 있는 초안이 하나도 없음, `GMS_KEY` 없음 |
| 502 | `model_output_truncated` | 모델 답이 출력 상한(`DESIGN_DRAFTS_LLM_OUTPUT_TOKEN_LIMIT`)에 닿아 끊김(S15P31A108-382) |
| 504 | `model_timeout` | `DESIGN_DRAFTS_LLM_TIMEOUT_SECONDS`(기본 150초, 0 초과 240 이하) 초과. 출력 상한 `DESIGN_DRAFTS_LLM_OUTPUT_TOKEN_LIMIT` 기본 16384(1~32768, 추론 토큰 포함 — S15P31A108-388 실측) |
| 502 | `design_drafts_failed` | 그 밖의 AI 내부 오류 |

## 8. 예시와 검증 자료

- `docs/api/examples/design-drafts/request.json`: 가상 기획서 `plan.md`에 #133 질문 7건(답변 4건, 닫힘 1건, 미답변 2건)과 기존 항목 3건(APPROVED 1, DRAFT 2)을 붙인 요청. `response.json`은 대본 가짜 공급자로 만든 응답, `errors.json`은 상태별 오류.
- `ai/evaluations/design_drafts/results-2026-11-03-dry.md`: 답변 표와 초안·변경 목록 표. 승인본 갱신(before 보존), 기존 초안 갱신, 답변 기반 신규, 원문만 기반 신규, 조작 인용 제거, 근거 없는 항목(맨 뒤), 동일 재진술 제외를 모두 담았다.

## 9. 담당자 확인 필요·미완료

- 오서현 제안으로 정리된 것(2026-11-03): 호출 범위(baseline 발행 전, PLN-A05 비동기 작업, 문서별 호출·재시도), 입력 범위(문서 출처 현재 항목, 설계 항목당 한 버전), 생성 후보 보관 후 채택, 생성 당시 `before`·근거 불변 보존, 반영 직전 변경 확인. 1절·6절에 반영했고 요청 검증에 설계 항목당 한 버전(`design_item_id` 중복 422)을 더했다.
- 합의 남음: 화면 202(후보 선택·비교, `conflicting_fields`·`support=NONE`·`unresolved` 표시), 생성 작업·후보 표 설계, 같은 항목의 후보가 여러 생성 결과에 있을 때의 선택 방식, DRAFT 채택 후 PLN-A06 목록 노출.
- `docs/api/03-ai-internal.md`에 AI-A13으로 추가할지, AI-A06 `/v1/bee/draft`(문서 수정안 텍스트)와의 관계 정리.
- `BEE_DRAFT` feature 사용 여부(기존 `llm_usage.feature` 값에 있음).
- 실제 모델 평가는 하지 않았다(가짜 공급자만). 프롬프트와 `unresolved` 서술 품질은 실측 뒤 조정한다.
- 영어 문서·`plain-v1` 입력의 품질은 미확인.

## 10. 검증

```text
cd ai
python -m pytest tests/test_design_drafts_schema.py tests/test_design_drafts_service.py tests/test_design_drafts_api.py tests/test_design_drafts_evaluation.py -q
python -m ruff check .
python -m ruff format --check .
python -m evaluations.design_drafts.run
```
