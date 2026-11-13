# 요구사항 누락 후보·보완 질문·답변 계약 초안 (#133)

작성: 박상은 · Jira: S15P31A108-133 · 대상: 오서현(BE 저장 PLN-A03/A04, 화면 S15P31A108-202), 후속 S15P31A108-134(답변 반영 설계 초안)

**제안 상태다.** 권한을 확인한 문서의 고정 버전과 원문에서 AI가 모호·누락·충돌 **검토 후보**와 후보별 **보완 질문**을 만들어 BE에 돌려주는 계약, 그리고 BE가 질문·답변을 저장할 때 필요한 필드 제안을 적는다. 저장·답변·확정은 BE가 맡고 AI는 아무것도 저장하지 않는다. 스토리 S15P31A108-91의 분할 작업이며, #128 구조화 추출은 선행 조건이 아니다. 실제 호출 경로·저장 컬럼·화면 표시는 담당자 합의가 남았다(10절).

## 1. 흐름과 책임

1. BE가 문서 업로드(DOC-A01) 뒤 분석 job(PLN-A01) 안에서 권한을 확인한 **문서 ID·버전·원문**을 AI에 보낸다. 원문은 BE가 strict UTF-8로 디코딩한 텍스트이고 해시·정규화·파서 프로필은 [#127/#223 파서 계약](../../ai/app/services/document_parsing/README.md)과 같다.
2. AI는 원문을 파싱해 블록·행 범위(`SourceLocator`)를 근거 단위로 만들고, 규칙 탐지와 모델 제안을 합쳐 후보를 만든다. 모델이 제시한 모든 인용은 해당 블록 원문의 부분 문자열인지 대조하며, 확인되지 않으면 후보를 `UNVERIFIED`로 표시한다. 우선순위로 정렬하고 질문 수 상한을 적용한다.
3. BE는 후보·질문을 `planning_question`에 저장하고(PLN-A03 목록), 사용자가 화면 202에서 답변하면(PLN-A04) 답변을 저장한다. #134는 저장된 질문·답변(`QuestionAnswer`)을 입력으로 쓴다.

구현: `ai/app/schemas/planning_questions.py`(모델), `ai/app/services/planning_questions/`(규칙·모델 제안·조합, [README](../../ai/app/services/planning_questions/README.md)), `ai/app/routers/planning_questions.py`(기본 비활성 경로). 예시: `docs/api/examples/planning-questions/`. 검토 자료: `ai/evaluations/planning_questions/`.

## 2. 탐지 기준과 근거 단위

근거 항목(`EvidenceItem`)은 00-common `Source`에 블록 정보를 더한 것이다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `document_id`, `document_version_id`, `version_no` | ID, ID, integer ≥ 1 | 분석한 고정 버전. 결과의 `document`와 항상 같다 |
| `block_id` | string | #127 파서 블록 ID |
| `locator` | `{type: "text_lines", start_line, end_line}` | 정규화 원문의 1-based 행 범위(마지막 행 포함) |
| `location` | string ≤ 1,000 | 표시용 "제목 경로 / L시작-L끝". 권한·검증 근거가 아니다 |
| `quote` | string 1~1,000 | 해당 블록 원문의 **정확한 부분 문자열**(앞뒤 공백 없음) |

| 신호(`signal`) | `kind` | 기준 |
| --- | --- | --- |
| `UNDECIDED_STATEMENT` | `MISSING_CANDIDATE` | 문서가 스스로 미결이라고 말한 문장: "정하지 않았다"·"합의하지 않았다"·"결정되지 않았다", "미정"·TBD·TODO, "추후 결정", "별도 정책에 따른다"·"후속 계약에서 정한다". 현재형("정의하지 않는다", "포함하지 않는다")은 같은 문장에 "이 문서"·"아직" 같은 문서 범위 표지가 있을 때만(동작 설명과 구분) |
| `PLACEHOLDER_CELL` | `MISSING_CANDIDATE` | 표 셀이 "미정"·TBD·TODO·"추후"·"추후 결정"·"미확정"·"?"로 **명시된** 경우. 같은 표·같은 열의 미정 칸은 후보·질문 하나로 묶고, 행 이름을 요약·질문에 나열하며 행마다 행 전체를 근거로 인용한다(근거 최대 5개). 미정 행이 하나면 `SHORT_ANSWER`, 둘 이상이면 `ESSAY`. 빈 칸은 미정 표시가 아니라서 제외(설명이 필요 없는 `id` 칸, 표 제약 행 — S15P31A108-413), `-`는 "없음"의 뜻으로 쓰여 제외 |
| `DUPLICATE_KEY` | `CONFLICT` | 같은 식별자(`REQ-003`)가 서로 다른 내용으로 2곳 이상. 동일 문장 반복은 제외 |
| `MODEL_PROPOSAL` | 모델이 고른 kind | 모델이 블록 ID·인용으로 제시한 후보. 3절 검증을 거친다 |

규칙은 문서가 말한 것만 잡는다. 주제 부재(예: 개인정보 정책이 없음)는 모델 제안 영역이며, 후보 0건은 문서가 완전하다는 뜻이 아니다. `kind`는 BE `planning_question.kind` 제약(`MISSING_CANDIDATE`, `CLARIFICATION`, `CONFLICT`)과 같은 값이다.

## 3. 검토 후보와 미확인 (완료 조건 3)

후보 `status`는 `REVIEW_CANDIDATE`와 `UNVERIFIED` 둘뿐이고 확정 상태는 스키마에 없다.

- `REVIEW_CANDIDATE`: 제시된 인용이 **모두** 블록 원문에서 확인됐고 근거가 1개 이상(CONFLICT는 서로 다른 블록 2개 이상).
- `UNVERIFIED`: 하나라도 확인되지 않았거나 근거가 없음. `unverified_reason`은 `BLOCK_NOT_FOUND`(없는 블록 ID), `QUOTE_NOT_IN_BLOCK`(인용이 블록 원문에 없음), `NO_EVIDENCE`(근거 없음), `INSUFFICIENT_EVIDENCE`(CONFLICT인데 블록 2개 미만). 확인되지 않은 인용문은 결과에 포함하지 않는다.
- 질문에는 후보 상태가 `basis`로 복사된다. 화면은 `basis=UNVERIFIED` 질문을 "근거 미확인"으로 구분해 보여 주고, 사용자가 후보를 닫을(`DISMISSED`) 수 있어야 한다.
- 모델 출력의 구조 오류(필드 누락, 허용되지 않은 kind, CHOICE 선택지 2개 미만 등)는 502 `model_unavailable`로 끝내고 실제 호출 usage를 보존한다. 빈 성공으로 바꾸지 않는다.

## 4. 요청: `POST /v1/planning-questions`

`INTERNAL_SERVICE` Bearer 인증, `Content-Type: application/json`, `X-Request-Id` 선택. 기본 비활성이며 `PLANNING_QUESTIONS_ENABLED=true`와 모델 모드의 `GMS_KEY`가 필요하다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | ID | Y | 권한 검사를 마친 프로젝트 |
| `request_key` | string ≤ 256 | Y | 분석 요청 식별값. 실제 모델 호출 key(`llm-planning-questions-<uuid>`)는 usage에 따로 기록 |
| `document` | `{document_id, document_version_id, version_no}` | Y | 분석할 고정 버전 |
| `kind` | `PLAN\|REQUIREMENTS\|API_SPEC\|ERD` | Y | 문서 종류 |
| `media_type` | `text/markdown\|text/plain` | Y | #223과 같음 |
| `content` | string ≤ 1 MiB | Y | 원문 |
| `content_hash`, `text_hash` | sha256 hex | Y | 원본 bytes 해시, 정규화 텍스트 해시(#127) |
| `normalization_version`, `parser_profile` | string | Y | `utf8-lf-v1`, `markdown-v1` 또는 `plain-v1` |
| `mode` | `MODEL\|RULES_ONLY` | N | 기본 `MODEL`. `RULES_ONLY`는 모델을 호출하지 않고 규칙 후보·템플릿 질문만 돌려준다(개발·대용량 문서용) |
| `max_questions` | integer 1~50 | N | 질문 수 상한. 기본 20 |
| `known_questions` | KnownQuestion[] ≤ 100 | N | 같은 프로젝트에서 이미 보이는 질문(4.1절). 기본 빈 배열 |

### 4.1 이미 있는 질문(`known_questions`, S15P31A108-414)

질문을 문서 버전 하나만 보고 만들어, 다른 문서에서 이미 묻거나 답한 결정을 다시 묻고 두 곳에 서로 다른 답이 남았다(dev 11-13, 재취소 처리를 API 명세와 다른 문서가 따로 물어 "409로 거부"와 "별도 오류 응답"으로 갈림). BE는 질문을 만들 때 같은 프로젝트의 기존 질문을 함께 보낸다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `question` | string 1~2,000 | 질문 문구 |
| `status` | `OPEN\|ANSWERED\|DISMISSED` | 미답변·답변됨·닫힘. `SUPERSEDED`는 보내지 않는다 |
| `answer` | string 1~200 또는 null | `ANSWERED`일 때만, 답의 앞 200자. 다른 상태는 null 또는 생략 |

- 담는 범위: PLN-A03 목록에 보이는 질문(저장된 `SUPERSEDED`와 조회할 때 대체로 판정된 질문 제외) 중 **이번 버전이 아닌 것**. 다른 문서의 질문과 같은 문서 이전 버전의 질문이다. 이번 버전의 질문은 8절 키 병합 대상이라 넣지 않는다. 등록을 취소한(DOC-A25) 문서의 질문은 답한 것도 넣지 않는다(철회된 결정이 다른 문서의 질문을 막지 않게).
- 답한 질문은 설계 초안의 `related_answers`([#134 계약 §2](answer-design-draft-contract-S15P31A108-134.md))에 넘어갈 수 있는 것만 넣는다: 구조 답(`answer_payload`)이 있고 질문이 2,000자 이하. 크기도 초안 쪽과 같게 **질문 + 구조 답 전체**(선택지·기타 입력·본문 글자 수)로 세고, 모델에는 답의 앞 200자만 보낸다. 같은 시점이면 여기 넣은 답한 질문은 초안 입력에 반드시 들어간다 — 질문은 생략했는데 초안이 그 결정을 모르는 일이 없게.
- 같은 문서 이전 버전의 **미답변(`OPEN`)** 질문은 넣지 않는다. 이번 버전이 그 문서의 기준 버전이라, 생성이 끝나면 이전 버전 미답변 질문은 대체로 판정돼 사라진다. 넣으면 모델이 그 결정을 묻지 않아 아무도 묻지 않게 된다.
- 다른 문서의 미답변 질문도 지금 답할 수 있는 것(PLN-A03 `freshness`가 `CURRENT`)만 넣는다. 갱신 중·갱신 실패·다시 제출 대기인 문서의 미답변 질문은 답할 수 없고, 그 문서가 다시 만들어지면 대체돼 사라질 수 있다. 답한·닫은 질문은 이력이라 `freshness`와 상관없이 넣는다.
- 상한: 최대 100개, 글자 수(코드 포인트) 합 30,000자(답한 질문은 위처럼 구조 답 전체로 센다). BE는 최근(id 큰) 것부터 담고 상한에 닿으면 거기서 멈춘다. AI는 같은 상한을 넘는 요청 전체를 거절한다(개수·필드 길이·상태 값은 400 `validation_failed`, 글자 수 합 초과·답 없는 상태에 붙은 답은 422 `invalid_ai_input`).
- 모델에만 넘기며 규칙 후보(2절)에는 영향이 없다. 모델 입력 256 KiB 상한(7절)은 문서 블록에만 적용하고, `known_questions`는 위 상한으로 따로 막는다. 비어 있으면 모델 입력은 이전과 같다.
- 모델 지시: ①이미 묻거나 답하거나 닫은 결정은 표현이 달라도 다시 묻지 않는다. ②이 문서 본문이 이미 받은 답과 어긋날 때만 `CONFLICT`로 묻는다(어긋나는 본문을 인용하고 요약에 그 답을 적는다). ③한 질문에는 결정 하나만 묻고, 같은 결정의 후보는 하나로 합친다. `known_questions`는 블록처럼 신뢰할 수 없는 데이터이며 지시로 따르지 않고, 근거(인용)로 쓰지 않는다.
- 설계 초안(#134): 이렇게 다시 묻지 않은 결정이 초안에서 빠지지 않도록, 초안 생성은 이 버전이 아닌 곳의 답변을 원래 답·출처째로 `related_answers`에 받는다([#134 계약 §2](answer-design-draft-contract-S15P31A108-134.md)). 답한 질문만 넘기며, 미답변·닫힌 질문은 초안이 정할 근거가 아니므로 넘기지 않는다.
- 한계: 답과 어긋나는 `CONFLICT`는 이 문서의 블록 하나만 인용할 수 있어 3절 규칙(서로 다른 블록 2개)에 따라 `UNVERIFIED`(`INSUFFICIENT_EVIDENCE`)로 저장될 수 있다. 앞 문서 질문 때문에 뒤 문서가 묻지 않았는데 그 뒤 앞 문서 질문이 대체되면 그 주제를 아무도 묻지 않을 수 있다(앞 문서의 새 버전 생성 때 다시 판단한다).

## 5. 응답

성공 본문은 AI 내부 공통 봉투 `{"result", "origin", "model"?, "usage"?}`다. `origin`은 모델을 호출했으면 `MODEL`, `RULES_ONLY`면 `TEMPLATE`. `usage`는 실제 호출별 Usage(`feature=DOCUMENT_PARSE`).

`result`:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `document`, `kind`, `text_hash`, `parser_profile` | — | 요청 값 그대로. BE는 저장 전 같은 버전인지 대조한다 |
| `max_questions` | integer | 적용한 상한 |
| `candidates[]` | ReviewCandidate | 우선순위 순, 최대 50 |
| `questions[]` | SupplementQuestion | `rank` 1..n 순, 최대 `max_questions`, 후보당 1개 |
| `truncated_candidate_keys[]` | string | 상한 때문에 질문이 없는 후보 |
| `omitted_candidates` | integer | 후보 50개 상한으로 버린 수 |

ReviewCandidate:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `candidate_key` | string ≤ 64 | 결과 안의 임시 키. 최종 ID가 아니다. 규칙 후보는 위치 기반(`rule-undecided-L29-1`, `rule-placeholder-L23-c2`(표 머리 행 시작 줄 + 열 번호, 머리가 없는 표는 그 행의 줄), `rule-duplicate-REQ-003`), 모델 후보는 **종류 + 확인된 근거(블록 ID·인용, 정렬) + 질문 유형·선택지 + 공백 정규화한 질문 문구의 해시**(`model-3f9a1c…` 12자리)다. 출력 순서와 무관해 같은 버전을 재분석해 같은 문장을 인용하고 같은 질문을 하면 같은 키가 나온다. 같은 문장을 근거로 한 서로 다른 질문은 키가 다르고 둘 다 남으며, 질문까지 같은 제안이 둘일 때만 첫 번째를 남긴다 |
| `kind` | `MISSING_CANDIDATE\|CLARIFICATION\|CONFLICT` | 2절 |
| `status` | `REVIEW_CANDIDATE\|UNVERIFIED` | 3절 |
| `signal` | `UNDECIDED_STATEMENT\|PLACEHOLDER_CELL\|DUPLICATE_KEY\|MODEL_PROPOSAL` | 탐지 경로 |
| `summary` | string ≤ 2,000 | 무엇이 비었거나 모호한지(한국어) |
| `evidence[]` | EvidenceItem | 확인된 근거만, 최대 5 |
| `unverified_reason` | string \| null | `UNVERIFIED`일 때만 |

SupplementQuestion:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `question_key` | string | `q-<candidate_key>` |
| `candidate_key` | string | 후보 키 |
| `rank` | integer ≥ 1 | 우선순위 순번 |
| `question_type` | `CHOICE\|SHORT_ANSWER\|ESSAY` | 선택·단답·서술 |
| `text` | string ≤ 2,000 | 질문(한국어) |
| `answer_format` | 6절 | 답변 형식 |
| `origin` | `MODEL\|TEMPLATE` | 모델 질문인지 규칙 템플릿인지 |
| `basis` | `REVIEW_CANDIDATE\|UNVERIFIED` | 후보 상태 복사 |

## 6. 질문 유형과 답변 형식

`answer_format`은 `type`으로 구분한다.

| `type` | 형식 필드 | 답변(`answer`) |
| --- | --- | --- |
| `CHOICE` | `options[]`(2~8, 서로 다름), `multiple`(기본 false), `allow_other`(기본 true) | `{type: "CHOICE", selected: string[], other_text?: string ≤ 500}`. `selected`는 `options` 부분집합, `multiple=false`면 최대 1개, `allow_other=false`면 `other_text` 금지, 둘 다 비면 거부 |
| `SHORT_ANSWER` | `max_length`(기본 200, ≤ 500) | `{type: "SHORT_ANSWER", text}` 공백만 금지 |
| `ESSAY` | `max_length`(기본 2,000, ≤ 4,000) | `{type: "ESSAY", text}` |

답변 저장 단위 `QuestionAnswer`: `{question_key, answer, answered_by?: ID, answered_at?: Time}`. 검증 함수 `validate_answer(question, answer)`가 위 규칙을 구현하며, BE가 PLN-A04에서 같은 규칙을 적용하거나 AI 검증을 호출하는 방식은 합의 대상이다. #134는 `QuestionAnswer[]`를 입력으로 받는 것을 전제한다.

## 7. 질문 수 상한과 우선순위

| 항목 | 값 |
| --- | --- |
| 질문 수 | `max_questions`(기본 20, 1~50). 후보당 1개 |
| 후보 수 | 최대 50, 초과분은 `omitted_candidates` |
| 모델 제안 | 최대 20건, 후보당 근거 최대 5 |
| 모델 입력 | 블록 JSON 256 KiB 초과 시 422 `invalid_ai_input`. 큰 문서는 `RULES_ONLY`로 먼저 처리 |
| 이미 있는 질문 | `known_questions` 최대 100개·30,000자, 답은 앞 200자(4.1절). 블록 256 KiB 상한과 따로 센다 |

우선순위 점수 = 상태(`REVIEW_CANDIDATE` 100, `UNVERIFIED` 0) + 종류(`CONFLICT` 30, `MISSING_CANDIDATE` 20, `CLARIFICATION` 10) + 근거 수(최대 3 × 2). 동점은 첫 근거의 시작 행이 앞선 순, 다음은 `candidate_key` 순. 근거 없는 후보는 항상 뒤다. 같은 종류·같은 블록에서 인용이 겹치는 검증 완료 모델 후보가 있으면 규칙 후보는 제거되고 모델 질문이 쓰인다. 단 `PLACEHOLDER_CELL` 열 후보는 미정 행이 근거 상한(5개) 이내이고 모델 후보 하나가 그 근거 행을 모두 인용할 때만 제거된다(한 행만 다룬 모델 질문이 같은 열 다른 행의 질문을 지우지 않게). 미정 행이 근거 상한을 넘은 열 후보는 모델 후보와 겹쳐도 제거하지 않는다. 모델 근거도 최대 5개라 근거에서 빠진 행은 열 질문만 묻기 때문이다.

## 8. 저장 계약 제안 (오서현 합의 대상)

현재 `planning_question`(V2)은 `kind`, `question`, `origin`, `status`, `answer TEXT`, `answered_by`, `answered_at`, `document_version_id`, `design_item_id`만 있다. 질문 유형·답변 형식·근거·우선순위를 저장하려면 아래 확장이 필요하다. 컬럼 이름은 제안이다.

| AI 필드 | 저장 제안 | 비고 |
| --- | --- | --- |
| `document.document_version_id` | `document_version_id` | 기존 컬럼 |
| `candidate.kind` | `kind` | 기존 CHECK 값과 동일 |
| `question.text` | `question` | 기존 |
| `question.origin` | `origin` (`model`/`template`) | 기존 CHECK에 소문자로 매핑 |
| `candidate_key`, `question_key` | `analysis_request_key TEXT`(요청 `request_key` 또는 BE 분석 job ID), `question_key TEXT`, UNIQUE(`document_version_id`, `analysis_request_key`, `question_key`) | 키는 **한 분석 결과 안에서만** 유일성을 보장한다. 행마다 어느 분석 실행에서 나왔는지 남긴다 |
| `question_type`, `answer_format` | `question_type TEXT`, `answer_format JSONB` | 화면 렌더링 |
| `basis` | `evidence_status TEXT` (`REVIEW_CANDIDATE`/`UNVERIFIED`) | "근거 미확인" 표시 |
| `candidate.summary`, `signal`, `unverified_reason` | `summary TEXT`, `signal TEXT`, `unverified_reason TEXT` | 검토 화면 설명 |
| `candidate.evidence[]` | `evidence JSONB` (block_id, locator, location, quote) 또는 별도 `planning_question_evidence` 테이블 | PLN-A03 응답 `source`는 `evidence[0]`을 00-common Source(`location` 포함)로 내보낸다 |
| `rank` | `rank INT` | 목록 정렬 |
| `answer` | 기존 `answer TEXT` 유지 + `answer_payload JSONB`(6절 구조) | CHOICE 선택을 텍스트로만 저장하면 #134가 선택지를 복원하지 못한다 |

처리 규칙 제안:

- 새 버전 분석 결과는 이전 버전 질문을 덮어쓰지 않는다.
- 같은 버전 재분석(PLN-A02 재시도 등)에서는 키를 **같은 질문인지 판단하는 힌트**로만 쓴다. 규칙 후보 키와 같은 근거를 인용한 모델 후보 키는 재분석에서도 같지만, 모델이 다른 문장을 인용하거나 요약 문구만 바꿔도 키가 달라진다. 따라서 BE는 (1) 이전 실행의 답변된(`ANSWERED`)·닫힌(`DISMISSED`) 행은 키가 같아도 질문 문구·형식을 덮어쓰지 않고 보존하고, (2) 새 결과에만 있는 키는 새 행으로 넣고, (3) 새 결과에 없는 미답변(`OPEN`) 행은 숨기거나 `SUPERSEDED` 상태(추가 필요)로 표시한다. 키가 같다는 이유로 기존 행의 질문 내용을 바꾸지 않는다.
- 종류·인용·질문 유형·선택지·문구까지 같은 모델 제안이 한 결과 안에 둘 이상이면 AI가 첫 번째만 남기므로 한 결과 안에서 키는 중복되지 않는다. 같은 문장을 근거로 한 서로 다른 질문은 각각 별도 행이다.
- 사용자가 후보를 닫으면 `status=DISMISSED`. `UNVERIFIED` 질문도 답변할 수 있지만 화면에 근거 미확인 표시를 유지한다.
- PLN-A04 요청 `answer: string`을 6절 `answer` 객체로 바꾸거나, 호환을 위해 `answer`(표시 텍스트)와 `answer_payload`를 함께 받는다.
- `origin=human`(사용자가 직접 추가한 질문)은 AI 결과에 없으며 BE 전용이다.

## 9. 오류

형식은 `{"error": {"code", "message"}}`이며 실제 모델 호출 뒤 실패면 `usage`를 덧붙인다. 파서 오류는 `details.reason`(파서 코드)과 가능한 경우 `details.locator`를 담는다. 예시: `docs/api/examples/planning-questions/errors.json`.

| 상태 | code | 상황 |
| --- | --- | --- |
| 401 | `invalid_internal_token` | 내부 토큰 누락·불일치 |
| 503 | `planning_questions_not_configured` | 기본 비활성 또는 한도 설정 오류 |
| 400 | `validation_failed` | JSON·필드·허용 값 오류 |
| 413 / 415 | `request_too_large` / `unsupported_media_type` | 본문 2 MiB 초과(설정값) / 미디어 타입 |
| 422 | `invalid_ai_input` | 파서 거부(`source_hash_mismatch`, `unsupported_construct` 등), 모델 입력 256 KiB 초과 |
| 502 | `model_unavailable` | 모델 호출 실패, 출력 구조 위반, `GMS_KEY` 없음 |
| 504 | `model_timeout` | 모델 시간 예산 초과(`PLANNING_QUESTIONS_LLM_TIMEOUT_SECONDS`, 기본 90초, 0 초과 240 이하. 출력 상한 `PLANNING_QUESTIONS_LLM_OUTPUT_TOKEN_LIMIT` 기본 8192, 1~16384, 추론 토큰 포함 — S15P31A108-388 실측) |
| 502 | `planning_questions_failed` | 그 밖의 AI 내부 오류 |

## 10. 담당자 확인 필요·미완료

- 오서현: 8절 저장 컬럼 확장과 PLN-A03/A04 응답 형식(`question_type`, `answer_format`, `basis`, `evidence`), PLN-A04 답변 객체 수용, 화면 202의 근거 미확인·상한 초과(`truncated_candidate_keys`) 표시, DISMISSED 경로.
- BE→AI 호출 위치(PLN-01 분석 job 안인지, 별도 job인지)와 시간 예산·usage 저장. feature 코드는 `DOCUMENT_PARSE`를 쓴다(`llm_usage.feature` 제약에 새 값을 추가하지 않음).
- `docs/api/03-ai-internal.md`에 AI-A12로 추가할지, AI-A01 `/v1/parse`의 `questions`를 이 응답으로 대체할지.
- 실제 모델 평가는 아직 하지 않았다(가짜 공급자 검토만). 프롬프트·템플릿 질문 문구는 실측 뒤 조정한다.
- 규칙 패턴은 한국어 문서 기준이며 영어 문서는 모델 제안에 의존한다.
- #134 입력으로 `QuestionAnswer[]`와 원 질문·근거를 함께 넘기는 방식.

## 11. 검증

```text
cd ai
python -m pytest tests/test_planning_questions_schema.py tests/test_planning_questions_rules.py tests/test_planning_questions_service.py tests/test_planning_questions_api.py tests/test_planning_questions_evaluation.py -q
python -m ruff check .
python -m ruff format --check .
python -m evaluations.planning_questions.run
```
