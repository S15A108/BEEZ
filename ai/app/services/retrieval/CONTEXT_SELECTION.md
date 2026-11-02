# 개발 컨텍스트 후보 선택 (#131)

`context_selection.select_context`는 백엔드가 권한을 확인해 제공한 현재 승인 기준 스냅샷에서 설계 항목과 승인 원문 근거를 선택하는 내부 구성요소다. 이 문서는 구현된 내부 인터페이스를 설명하며 BE·AI HTTP 계약 합의나 MCP 연동 완료를 뜻하지 않는다.

개발 요청의 기능·도메인·키워드 추출은 별도의 [검색 보조 후보 문서](../../../../docs/experiments/context-request-analysis-S15P31A108-131.md)를 참고한다. 이 분석기는 아직 선택기나 BE 검색 호출에 연결되지 않았다.

## 경계와 입력

- BE는 현재 멤버십, 프로젝트, task 소속과 단일 시점의 기준을 확인한 후 `SelectionInput`을 구성하고 결과 사용 전 권한과 현재 기준을 재확인한다. 이 모듈은 DB에 접근하거나 권한·현재성을 독립적으로 증명하지 않는다.
- `items`는 해당 프로젝트·기준의 `APPROVED` 항목과 항목 ID·버전 ID이고, `document_version_ids`는 해당 기준에 속한 승인 문서 버전이다. `evidence`의 프로젝트·기준·문서 버전은 이 입력과 일치해야 한다. 원문 근거는 설계 항목이 아니다.
- 선택 `task_id`의 현재 계획 참조는 `task_design_refs`에 항목 ID와 당시 항목 버전 ID를 함께 담는다. 입력된 버전이 현재 기준과 다르거나 항목이 없으면 실패한다. 과거 참조를 현재 버전으로 치환하지 않는다. 작업 생성·상태 변경·시작 허가는 하지 않는다.
- 관계는 `DEPENDS_ON`, `EXPLICIT`, `CONFIRMED`이며 양 끝 항목 및 버전이 현재 입력과 일치하는 경우만 사용한다. `from`에서 `to` 방향 1단계이며 직접 선택되거나 계획에 참조된 항목에서만 확장한다. **전달받은 관계 중** 해당 시작점에서 출발한 확정 명시 관계의 대상이 없거나 양 끝 버전이 입력과 다르면 관계를 확장하지 않고 `unresolved`에 `DEPENDENCY_UNVERIFIED`를 표시한다. 입력에 아예 빠진 관계는 감지할 수 없으므로 이 표시가 없다고 관계 조회가 완전하다는 뜻은 아니다. 이 표시는 원인이나 실제 최신성·권한을 판정하지 않고 누락 대상 ID·개수도 드러내지 않는다. 후보·재확인 필요·역방향·2단계 관계는 이 표시를 만들지 않는다. 양 끝 버전은 아직 BE 연동 계약이 아니며 BE가 확인한 값을 제공해야 한다.
- `search_status=COMPLETE`인 결과만 선택한다. 이는 검색 실행 상태이며 의존 확장의 완전성을 보장하지 않는다. `NOT_READY`와 `FAILED`는 오류이고 요청과 직접 매칭된 항목·원문이 모두 없으면 `NO_MATCH`로 구분한다. 작업 계획 참조나 그 의존 항목이 추가돼도 직접 매칭이 없다면 `NO_MATCH`를 유지하고, 확정 관계가 검증되지 않으면 `DEPENDENCY_UNVERIFIED`도 함께 반환한다. 이 코드는 BE 검색 결과를 직접 가져오지 않으며 정상 0건은 정보 부족을 뜻한다. 설계에 기능이 없다는 확정 판정이 아니다.

## 사용

`SelectionInput`에 스냅샷, 개발 요청, 선택적 계획 참조, 승인 항목·원문 근거와 관계를 넣는다. `async select_context(input, selector)`의 selector는 `async select(input) -> SelectionProposal`을 구현해 **입력 안의** `design_item_version_ids`와 `evidence_ids`를 반환한다. 입력에 없는 ID나 중복 ID는 실패한다. 결과에는 각 항목별 `MATCHED`, `TASK_PLAN`, `DEPENDENCY` 이유와 선택된 원문 근거 ID가 있다. 임베딩 선택기가 실제 호출한 사용량은 결과 또는 오류의 `usage`에서 확인한다.

```python
result = await select_context(snapshot, selector)
```

선택기의 예외는 빈 선택으로 바꾸지 않는다. 임베딩 실패·제한 초과 등의 오류는 호출자에게 전달하며 호출된 경우에만 비용이 생긴다. 테스트의 fake provider/fixture는 유료 모델을 호출하지 않는다. #129 임베딩을 실제로 쓰는 선택기는 설정된 공급자를 호출하므로 사용량과 비용이 발생할 수 있다.

### 임베딩 선택기 구성 예시

아래는 연결 방식의 예시다. `snapshot`은 권한과 기준을 검증한 BE 결과이며 `chosen_*` 값은 호출자가 평가 후 정해야 한다. 임계값은 관련성을 보장하지 않고 너무 높으면 관련 항목을 놓칠 수 있다. 실제 키를 소스에 적지 말고 `ai/.env` 또는 배포 설정으로 주입한다.

```python
import httpx

from app.core.config import get_settings
from app.schemas.retrieval import EmbeddingPolicy
from app.services.retrieval.context_selection import select_context
from app.services.retrieval.context_semantic_selector import EmbeddingContextSelector
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import EmbeddingTokenCounter, embedding_profile

settings = get_settings()
if settings.gms_key is None:
    raise RuntimeError("GMS_KEY is required")

# 기존 /v1/embeddings 경로와 같은 리디렉션·프록시 정책.
async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as http:
    selector = EmbeddingContextSelector(
        provider=GmsEmbeddingProvider(http, settings.gms_key),
        profile=embedding_profile(settings),
        counter=EmbeddingTokenCounter(),
        min_similarity=chosen_similarity_threshold,
        max_items=chosen_max_items,
        max_evidence=chosen_max_evidence,
        policy=EmbeddingPolicy(),
    )
    result = await select_context(snapshot, selector)
    # 호출자는 result.usage를 프로젝트·요청에 맞춰 저장한다.
```

`max_items` 또는 `max_evidence`를 0으로 지정하면 해당 종류는 선택하지 않는다. 그 종류의 후보가 실제로 존재하면 다른 종류에서 일치 항목이 있더라도 전체 결과가 불완전하므로 공급자 호출 전에 내부 오류 `selection_incomplete`로 실패한다. 후보가 없는 종류는 0으로 설정해도 된다. 이 오류는 내부 모듈 경계의 상태이며 BE·MCP의 HTTP 오류 계약이 확정됐다는 뜻이 아니다.

이 선택기는 요청 문장과 **전달받은 각 후보 텍스트를 매 호출 새로 임베딩**해 코사인 유사도로 고른다. BE 색인의 벡터나 검색 결과를 재사용하지 않는다. 후보 수·길이와 재시도에 비례해 유료 호출 및 지연이 발생하며, `EmbeddingPolicy`의 배치·호출/전체 시간·입력 한도는 질의와 후보 임베딩 단계에 각각 적용된다. 성공 시 `result.usage`, 오류 시 `ContextSelectionError.usage`에 호출 기록이 담긴다. 비용 기록 저장은 호출자 책임이며 알 수 없는 토큰을 0으로 기록하지 않는다. 취소나 프로세스 종료 시 진행 중 호출의 사용량을 확인하지 못할 수 있다.

선택 결과의 `direct_match_truncation`은 **전달받은 후보 중** 임계값을 통과했지만 직접 선택 상한을 넘어 제외된 설계(`items`)와 원문(`evidence`)의 수다. 상한과 정렬은 바꾸지 않는다. 이 수는 전체 BE 검색 범위, 실제 관련성이나 최종 결과에서 빠진 항목 수를 뜻하지 않는다. 직접 선택에서 제외된 설계가 작업 참조 또는 의존성으로 최종 결과에 포함될 수 있기 때문이다. 선택기가 이 정보를 제공하지 않거나 호출되지 않으면 값은 `null`이며 0으로 가정하지 않는다. 직접 선택 결과가 하나도 없지만 상한 때문에 제외된 후보가 있다고 보고되면 `NO_MATCH`가 아니라 `selection_incomplete`로 실패하고 사용량을 보존한다. 일치 표식도 이 수의 변경을 감지한다. 이 필드는 내부 선택 결과에만 적용하며 외부 BE/MCP 계약을 확정하지 않는다.

```text
cd ai
python -m pytest tests/test_context_selection.py -q
python -m ruff check .
python -m ruff format --check .
```

## 남은 연동

- 실제 APPROVED 검색 결과·관계와 작업 계획 조회, 현재성·권한 재검사는 BE에서 연결해야 한다. 실제 API 계약과 검색 실패·감사 방식은 담당자 합의가 필요하다.
- #132 패킷 구성·저장 및 #179 MCP-01 연결은 이 모듈에 포함되지 않는다. fixture 성공은 실제 MCP E2E가 아니다.
- `context_unresolved.review_unresolved`는 **주입된** 분석기의 `POSSIBLE_CONFLICT`·`INSUFFICIENT_EVIDENCE` 후보를 내부에서 검증한다. 선택 결과와 같은 승인 스냅샷에서 골라진 항목·근거만 분석기에 전달한다. 각 인용은 선택된 ID와 해당 텍스트의 정확한 부분 문자열이어야 하고 충돌 후보는 서로 다른 선택 ID 2개 이상이 필요하다. 서로 다른 ID가 독립 문서라는 뜻은 아니다. 결과는 `selected_context_only` 범위의 검토 후보이지 승인이나 충돌·요구사항 부재의 확정이 아니다. 빈 후보가 충돌이 없다는 증거도 아니다.
- 선택기는 요청·스냅샷과 선택 결과에 대한 내부 일치 표식을 만들고, 후보 분석 전에 같은 입력과 결과인지 확인한다. 다른 요청·작업·기준·후보 본문 또는 바뀐 선택 결과는 분석기를 호출하기 전에 거부한다. 이 표식은 직렬화 및 표시에서 제외하므로 직렬화 후에는 원래 선택 결과를 그대로 재사용할 수 없다. 해당 표식은 프로세스 내부 실수 방지용이며 인증·서명·DB 최신성 증명이 아니다. 서비스 간 전달이나 저장 계약은 #132와 별도로 정한다.
- 이 검증은 인용 문자열의 출처만 확인하며 **인용의 의미나 실제 충돌 여부를 보증하지 않는다**. 호출자는 승인 스냅샷과 선택 결과가 같은 요청·기준에서 왔음을 보장하고 사용 전 권한과 최신성을 다시 확인해야 한다. 텍스트는 모델 지시가 아닌 비신뢰 데이터다. 모델 호출 전에 입력 한도·민감정보·프롬프트 경계와 비용 정책도 호출자가 정한다. 잘못된 모델 출력은 `invalid_output`으로 실패하고 실제 사용량을 보존한다. `NO_MATCH`는 선택 결과가 비었다는 뜻으로 별도 보존한다.
- `LLMUnresolvedAssessor`는 선택된 원문만 JSON 데이터로 구성하고 공통 `LLMClient`에 JSON 후보 생성을 요청하는 내부 어댑터다. 모델·호출 키·시간 제한·`LLMContext`를 호출자가 명시하며, 프로젝트와 선택적 작업 ID가 입력과 다르면 모델을 호출하지 않는다. 모델 응답은 반드시 `review_unresolved`를 거쳐 출처 검증을 받아야 한다. 문서와 요청은 시스템 지시가 아닌 비신뢰 데이터로 취급하도록 프롬프트에 명시하지만 이것만으로 프롬프트 인젝션 차단을 보증하지 않는다.
- **#131의 운영 usage feature와 HTTP/MCP 연결은 아직 정해지지 않았다.** 어댑터는 사용량 기능을 자동 지정하지 않으며, 공통 클라이언트에 넘기는 `LLMContext.feature`가 실제 기능을 올바르게 분류하는지는 담당자 간 계약과 BE 저장 경로에서 확인해야 한다. 테스트의 `BEE_CHAT` 값은 테스트 대역 전용이며 #131 운영 분류로 합의된 값이 아니다. 설정·비용·보존 정책을 정하기 전에는 운영 엔드포인트에 연결하지 않는다. 별도의 독립 라벨과 실제 모델 평가 없이는 의미적 요구사항 누락·충돌을 감지했다고 주장하지 않는다.

- 임베딩 선택기는 유료 질의 호출 전에 문서 후보 전체의 토큰·배치 제한을 검사한다. 실제 문서 임베딩에서도 기존 검증을 다시 수행하므로 토큰화 비용이 두 번 들며, 사전 검증은 이후 모델 호출 성공을 보장하지 않는다.
