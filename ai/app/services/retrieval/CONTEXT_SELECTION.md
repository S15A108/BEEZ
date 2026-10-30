# 개발 컨텍스트 후보 선택 (#131)

`context_selection.select_context`는 백엔드가 권한을 확인해 제공한 현재 승인 기준 스냅샷에서 설계 항목과 승인 원문 근거를 선택하는 내부 구성요소다. 이 문서는 구현된 내부 인터페이스를 설명하며 BE·AI HTTP 계약 합의나 MCP 연동 완료를 뜻하지 않는다.

## 경계와 입력

- BE는 현재 멤버십, 프로젝트, task 소속과 단일 시점의 기준을 확인한 후 `SelectionInput`을 구성하고 결과 사용 전 권한과 현재 기준을 재확인한다. 이 모듈은 DB에 접근하거나 권한·현재성을 독립적으로 증명하지 않는다.
- `items`는 해당 프로젝트·기준의 `APPROVED` 항목과 항목 ID·버전 ID이고, `document_version_ids`는 해당 기준에 속한 승인 문서 버전이다. `evidence`의 프로젝트·기준·문서 버전은 이 입력과 일치해야 한다. 원문 근거는 설계 항목이 아니다.
- 선택 `task_id`의 현재 계획 참조는 `task_design_refs`에 항목 ID와 당시 항목 버전 ID를 함께 담는다. 입력된 버전이 현재 기준과 다르거나 항목이 없으면 실패한다. 과거 참조를 현재 버전으로 치환하지 않는다. 작업 생성·상태 변경·시작 허가는 하지 않는다.
- 관계는 `DEPENDS_ON`, `EXPLICIT`, `CONFIRMED`이며 양 끝 항목 및 버전이 현재 입력과 일치하는 경우만 사용한다. `from`에서 `to` 방향 1단계이며 직접 선택되거나 계획에 참조된 항목에서만 확장한다. 누락·과거 버전·후보·재확인 필요 관계는 확장하지 않는다. 양 끝 버전은 아직 BE 연동 계약이 아니며 BE가 확인한 값을 제공해야 한다.
- `search_status=COMPLETE`인 결과만 선택한다. `NOT_READY`와 `FAILED`는 오류이고 정상 0건은 `NO_MATCH`로 구분한다. 이 코드는 BE 검색 결과를 직접 가져오지 않으며 정상 0건은 정보 부족을 뜻한다. 설계에 기능이 없다는 확정 판정이 아니다.

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

이 선택기는 요청 문장과 **전달받은 각 후보 텍스트를 매 호출 새로 임베딩**해 코사인 유사도로 고른다. BE 색인의 벡터나 검색 결과를 재사용하지 않는다. 후보 수·길이와 재시도에 비례해 유료 호출 및 지연이 발생하며, `EmbeddingPolicy`의 배치·호출/전체 시간·입력 한도는 질의와 후보 임베딩 단계에 각각 적용된다. 성공 시 `result.usage`, 오류 시 `ContextSelectionError.usage`에 호출 기록이 담긴다. 비용 기록 저장은 호출자 책임이며 알 수 없는 토큰을 0으로 기록하지 않는다. 취소나 프로세스 종료 시 진행 중 호출의 사용량을 확인하지 못할 수 있다.

```text
cd ai
python -m pytest tests/test_context_selection.py -q
python -m ruff check .
python -m ruff format --check .
```

## 남은 연동

- 실제 APPROVED 검색 결과·관계와 작업 계획 조회, 현재성·권한 재검사는 BE에서 연결해야 한다. 실제 API 계약과 검색 실패·감사 방식은 담당자 합의가 필요하다.
- #132 패킷 구성·저장 및 #179 MCP-01 연결은 이 모듈에 포함되지 않는다. fixture 성공은 실제 MCP E2E가 아니다.
- 의미적 요구사항 부재·문서 간 충돌을 판정하거나 이를 자동으로 확정하지 않는다. `NO_MATCH`는 검색·선택 결과가 비었다는 뜻이다. 사람 검토 가능한 근거와 오류 분류를 포함한 별도 설계가 필요하다.
