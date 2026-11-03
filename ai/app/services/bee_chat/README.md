# 비비 초기화·설계 대화·근거 응답 (#135)

프로젝트 생성 뒤 **비비 인스턴스(페르소나·고정 시스템 프롬프트·프로젝트 프로필)** 를 결정적으로 만들고, 팀원의 설계 질문에 BE가 권한 검사를 마친 **검색 근거(READY 청크)·승인 설계 항목·관련 작업**만으로 답한다. 모든 인용·항목·작업 참조를 입력과 대조하고, 원문에 없는 인용이나 인용·참조가 뒷받침하지 않는 사실(숫자·키·경로·식별자·코드)이 하나라도 있으면 출력 전체를 거부하며, 근거를 하나도 대지 않은 답은 고정 문구("찾지 못했어요")로 바꾼다. 저장·answer_id 발급·피드백·대화 보존은 BE(오서현) 담당이고 이 모듈은 아무것도 저장하지 않는다.

계약 초안: [docs/design/bee-chat-contract-S15P31A108-135.md](../../../../docs/design/bee-chat-contract-S15P31A108-135.md). 예시: `docs/api/examples/bee-chat/`.

## 구성

| 파일 | 역할 |
| --- | --- |
| `app/schemas/bee_chat.py` | 인스턴스 요청·결과, 질문 요청(검색 범위·근거·승인 항목·관련 작업·이력), 인용·결과 pydantic strict 모델 |
| `instance.py` | 페르소나·인사말과 **고정** 시스템 프롬프트(`render_system_prompt`), 인스턴스 생성(`build_instance`, 모델 호출 없음) |
| `proposer.py` | 공통 `LLMClient`를 주입받는 답변 어댑터(`LLMAnswerProposer`)와 모델 출력 모델(`ModelAnswer`). 프로젝트 프로필·근거·항목·작업·이력은 사용자 메시지의 JSON 데이터이고 긴 텍스트는 `clip_text`로 2,000자 절단 |
| `service.py` | 근거 선택(상한) → 모델 답변 → 인용·참조·사실 토큰 검증(거부 또는 번호 정리) → 고정 문구 → 봉투 |
| `app/routers/bee_chat.py` | 기본 비활성 내부 경로 `POST /v1/bee/instances`, `POST /v1/bee/chat`. 요청 스키마는 `app/core/openapi.py`로 `$defs` 참조를 풀어 OpenAPI 문서에 싣는다 |

## 동작

1. **의미 검색은 BE 몫이다.** BE가 AI-A04로 질문을 임베딩하고 허용 집합(현재 baseline 또는 명시 검토 버전)·READY·활성 프로필 안에서 top-k를 구해 `retrieved_evidence`로 보낸다. 이 모듈은 임베딩이나 DB를 호출하지 않는다. 요청 검증에서 근거가 `scope` 안인지 확인한다. 승인 항목은 BE가 보존한 출처가 더 오래된 문서 버전이어도 받고(APPROVED), `DOCUMENT_REVIEW`에서는 승인 항목을 받지 않는다.
2. **근거 선택**: BE 순서(검색 순위)를 그대로 두고 `max_evidence`(기본 12)까지만 모델에 보인다. 넘친 수는 `evidence_omitted`와 `notices`에 남는다.
3. **응답 생성**: 시스템 메시지는 프로젝트와 무관한 고정 규칙이다. 프로젝트 이름·소개(`instance.project`), 근거 원문, 승인 항목, 관련 작업(ID·키·상태·담당자·계획), 이력은 모두 사용자 메시지의 데이터로 간다. 이력 턴과 계획 제목·목표는 모델에 앞 2,000자만 보인다(`clip_text`). 모델은 `{status, text, citations[{evidence_id, quote, claim}], design_refs, task_refs, unresolved, next_actions}`를 돌려준다.
4. **검증**
   - 입력에 없는 `evidence_id`·설계 항목 버전 ID·작업 ID, **근거 원문에 없는 `quote`**, 1..인용 수 밖의 본문 `[n]`(`[0]` 포함), 빈 `ANSWERED` 본문은 모순이므로 **출력 전체를 거부**한다(`invalid_output`, usage는 실패로 기록, HTTP 502). 원문에 없는 인용을 그 인용만 지우면 그 주장이 본문에 남으므로 거부한다.
   - **사실 토큰 검사(문장 단위)**(`claim_segments`·`support_tokens`·`unsupported_facts`): 본문을 문장으로 나누고 각 문장의 숫자(천 단위 쉼표 무시), `TASK-001`류 키, `/` 경로(끝 문장 부호 제외), 점·밑줄·camelCase 식별자, 3자 이상 대문자 코드가 **그 문장의 `[n]`이 가리키는 인용문**(과 문서·절 제목) 또는 참조한 항목(키·종류·본문)·참조한 작업(키·상태·담당자·계획)의 토큰과 독립 토큰으로 일치해야 한다. 인용문이 속한 청크의 다른 문장은 근거가 아니다. 문장 끝 뒤의 `[n]`은 앞 문장에 붙는다. 30 MB를 인용하고 50 MB라고 쓴 답, 150 MB 원문에 50 MB 답, 같은 청크의 다른 문장 숫자를 쓴 답이 여기서 걸린다. 질문은 근거로 치지 않는다. 토큰이 같은 의미 모순(부정 등)은 잡지 못한다.