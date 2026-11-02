# 개발 컨텍스트 내부 파이프라인 (#131)

`build_development_context`는 호출자가 제공한 동일 `SelectionInput`을 사용해 요청 분석 → 승인 후보 선택과 명시 관계 1단계 확장 → 미확정 후보의 인용 검증 순서로 실행한다. 선택 전 `NOT_READY`·`FAILED`를 각각 `context_not_ready`·`search_failed`로 반환하며 모델·임베딩을 호출하지 않는다. 정상 검색 0건은 `NO_MATCH`를 유지한다.

```python
from app.services.retrieval.context_pipeline import build_development_context

# snapshot은 BE가 권한·현재 승인 기준을 확인해 구성한다.
# analyzer, selector, assessor와 모델·호출 키·사용량 분류는 호출자가 주입한다.
result = await build_development_context(snapshot, analyzer, selector, assessor)
# result.hints: 검색 보조 후보; result.selection: 선택 결과
# result.review: 선택된 범위의 검토 후보
# result.llm_usage, result.embedding_usage: 시작된 호출의 사용량
```

분석 결과의 `query`가 원래 요청과 다르면 선택 전에 실패한다. 기능·도메인·키워드는 별도 보조 정보이며 선택 필터, 권한, baseline 또는 검색 질의 변경에 사용하지 않는다. 이 파이프라인은 주입된 분석기가 `LLMRequestAnalyzer`처럼 원문에 존재하는 후보만 반환하고 사용량을 정확히 전달하는 **신뢰할 수 있는 컴포넌트**라고 가정한다. 보조 후보 자체를 여기서 재검증하지 않는다.

`ContextPipelineError`는 단계와 정제된 오류 코드를 반환한다. 모델 오류 세부 `cause_code`는 `invalid_input`, `invalid_output`, `model_timeout`, `model_unavailable`만 노출하고 이외의 문자열은 버린다. 완료된 LLM·임베딩 기록과 실패 단계가 제공한 호출 기록은 각각 `llm_usage`, `embedding_usage`에 유지한다. 개별 단계 결과에도 사용량이 있으므로 중복 저장하지 않는다. 프로세스 종료나 공급자가 사용량을 제공하지 않은 호출의 실제 토큰은 이 모듈만으로 복구할 수 없다.

취소는 완료 결과나 일반 오류로 바꾸지 않는다. 전파된 `asyncio.CancelledError`에 완료·진행 중 호출의 `llm_usage`, `embedding_usage`를 붙인다. 바깥 `asyncio.timeout`이 이를 `TimeoutError`로 바꾼 경우 호출자는 해당 예외의 `__cause__`에 연결된 취소 예외를 확인해야 한다. 단, 강제 종료나 사용량을 전달하지 않는 주입 컴포넌트는 보장할 수 없다.

이 코드는 BE 검색·관계·계획 조회, 인증, 권한 검사, 기준·관계·계획의 최신성 검사, MCP 전송 및 패킷 저장을 구현하지 않는다. 호출자가 실제 승인 스냅샷을 일관되게 수집하고 사용 직전 권한과 최신성을 재확인해야 한다. 모델 기반 해석은 가짜 공급자만으로 동작을 시험했으며 실제 모델의 정확성, 운영 usage feature, 외부 데이터 전송 정책 및 비용은 별도 합의·검증이 필요하다.

현재 API에서는 BE의 검색·스냅샷 수집이 이미 완료된 뒤 분석이 시작된다. 따라서 이 분석 단계가 BE 검색어를 생성하거나 실제 BE 검색 품질을 개선하는 흐름은 아직 구현되지 않았다. BE 검색 전에 분석 결과를 쓰려면 오류·길이·usage·권한 범위를 포함하는 별도 호출 계약과 실측이 필요하다.
