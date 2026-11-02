# 개발 요청 검색 보조 후보 (#131)

`LLMRequestAnalyzer`는 개발 요청에서 기능·도메인·키워드의 **임시 검색 보조 후보**를 추출하는 내부 구성요소다. 원문을 `SearchHints.query`에 그대로 보존하며, 후보가 없더라도 원문 질의를 사용할 수 있다. 이것은 승인된 설계 조회나 검색 결과가 아니고 후보를 권한·프로젝트·기준·도메인 필터로 사용하지 않는다. 실제 BE 검색, 권한 검사, 최신 기준 검사, MCP 연결은 별도 작업이다.

호출자는 공통 `LLMClient`, 모델, 호출 키, 시간 제한, `LLMContext`를 명시한다. 이 모듈은 운영 사용량 feature를 선택하거나 모델을 설정하지 않는다. #131의 운영 feature·저장 경로·민감정보 처리·비용 및 검색 연동 계약은 합의가 필요하다. 테스트에 사용된 `BEE_CHAT`은 가짜 공급자 전용이다.

```python
from app.services.retrieval.context_request_analysis import (
    DevelopmentRequest,
    LLMRequestAnalyzer,
)

# client, context, model, call_key, timeout_seconds는 호출자가 명시한다.
analyzer = LLMRequestAnalyzer(
    client, context=context, model=model, call_key=call_key,
    timeout_seconds=timeout_seconds,
)
hints = await analyzer.analyze(
    DevelopmentRequest(project_id=project_id, task_id=task_id, text=request_text)
)
# hints.query는 원문이고 hints.usage는 실제 호출 기록이다.
```

요청은 공백이 아닌 최대 2,000자이며 프로젝트와 선택적 작업 ID는 호출 context와 같아야 한다. 모델 출력은 세 배열을 모두 포함해야 하고, 각 배열에 최대 8개의 항목을 허용한다. 각 항목은 앞뒤 공백이 없는 최대 80자의 정확한 요청 부분 문자열이어야 하며 같은 배열의 중복은 실패한다. 서로 다른 범주의 동일 문자열은 허용한다. 원문에 없는 동의어나 해석어는 거부한다. 출력 검증 실패는 `invalid_output`이며 이미 발생한 사용량을 오류에 보존한다. 입력·범위 오류는 모델 호출 전에 실패한다.

정확한 부분 문자열 검사는 출처만 확인한다. 추출의 의미적 정확성이나 검색 품질, 프롬프트 인젝션 차단은 보장하지 않는다. 빈 배열은 정보 부재의 증거가 아니다. 다른 언어, 조사·형태 변화, 축약어 및 간접 표현은 놓칠 수 있으며 실제 모델·독립 라벨 평가가 필요하다. 원문은 민감정보를 포함할 수 있으므로 호출 전에 처리·전송 정책을 확인한다.

이 분석기는 아직 선택기나 BE 검색 호출 경로에 연결되지 않았다. 내부 요청은 최대 2,000자지만 현재 BE DOC-A11 검색어 `q`는 최대 1,000자이므로 실제 연동 전에 길이 초과를 명시적으로 처리하는 계약이 필요하다. 여기에서 원문을 임의로 자르거나 검색 성공을 가정하지 않는다.
