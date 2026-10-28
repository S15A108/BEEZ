# 공통 LLM 클라이언트 (S15P31A108-79)

`LLMClient`는 AI 기능의 외부 모델 호출, 시간 예산·실패 분류와 실제 호출별 사용량을 공통 처리한다. 제공자와 모델명은 호출 측에서 주입한다. 각 AI 기능은 자신의 프롬프트·모델 설정·업무별 출력 검증과 근거 확인을 소유한다. DB 저장·집계와 검증의 최종 판정은 BE 책임이다.

## 실행 가능한 재사용 예제

`ai/`에서 다음 명령을 실행한다. 작업 ID가 없는 비비 대화와 작업·검증 ID가 있는 검증 호출을 같은 클라이언트로 수행한다.

```text
python -m examples.llm_reuse
```

예제는 `ExampleProvider`의 합성 응답·사용량만 사용한다. 외부 네트워크·자격 증명을 사용하지 않으며 예제 usage를 운영 기록으로 저장하지 않는다. 현재 원문 파서에는 LLM 호출이 없으므로 위 코드는 박상은 담당 기능에서 재사용할 수 있는 예제다. 실제 기능 연결과 담당자 확인은 별도로 진행한다.

## 호출과 제공자 주입

```python
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    OpenAICompatibleProvider,
)

# endpoint와 api_key는 실행 환경의 비밀 저장소/설정에서 제공한다.
# 예제 변수에 실제 자격 증명을 직접 적거나 로그로 출력하지 않는다.
async with OpenAICompatibleProvider(endpoint=endpoint, api_key=api_key) as provider:
    client = LLMClient(provider)
    request = LLMRequest(
        model=model,
        messages=(LLMMessage(role="user", content="문서 내용을 설명해줘"),),
        context=LLMContext(
            project_id="101",
            feature="BEE_CHAT",
            request_key=request_key,
        ),
        call_keys=(unique_call_key,),
        timeout_seconds=20.0,
        response_format={"type": "json_object"},
    )

    try:
        response = await client.generate(request)
    except LLMError as error:
        # 호출 후 실패라면 error.usage에 실제 호출별 사용량이 보존된다.
        error_envelope = error.to_api_dict()
        raise
    else:
        internal_envelope = response.to_api_dict()
```

위 호출은 async 함수 안에서 사용한다. `endpoint`, `api_key`, `model`, `request_key`, `unique_call_key`는 호출 측이 제공하는 값이다. 실제 공급자의 URL·모델·지원 옵션은 팀이 선택한 환경에 맞춰 설정한다. 어댑터는 [OpenAI Chat Completions 요청·응답 형식](https://platform.openai.com/docs/api-reference/chat/create)을 대상으로 하며 다른 공급자의 호환성을 자동으로 보장하지 않는다. `LLMProvider` 프로토콜을 구현한 fake나 별도 어댑터도 주입할 수 있다.

이 예제의 컨텍스트는 재사용 범위가 끝날 때 연결을 닫는다. 운영 서버에서는 앱 시작 시 provider와 `LLMClient`를 만들고 같은 이벤트 루프에서 여러 호출이 공유하도록 한다. 앱 종료 시 진행 중인 호출이 모두 끝난 뒤 `await provider.aclose()`로 정리한다. 요청마다 provider를 새로 만들면 연결 재사용 효과가 없다. 주입한 transport의 소유권도 provider에 있으므로 provider를 닫으면 transport도 닫힌다. 컨텍스트나 `aclose()`로 종료한 provider는 다시 사용하지 않는다.

이 어댑터는 텍스트 생성용 Chat Completions 경로를 구현한다. 공통 feature 목록에 `EMBEDDING`이 있어도 벡터 임베딩 엔드포인트나 다른 공급자 프로토콜은 별도 어댑터가 필요하다. endpoint·키의 설정 오류는 공급자 생성 시 안전한 `ValueError`로 보고하며 이 시점에는 모델 호출과 usage가 없다. 기능이 독립적으로 실패하도록 설정 검증을 각 기능의 초기화·호출 경계에서 처리한다.

공급자가 요청한 모델명과 다른 실제 버전·별칭을 반환한다면 `LLMRequest.allowed_response_models`에 허용할 이름을 명시한다. 기본값은 요청 모델만 허용한다. 허용하지 않은 모델 문자열은 출력 오류로 처리하고 응답에 그대로 노출하지 않는다.

`response_format`은 제공자에 전달하는 옵션이다. 공통 클라이언트의 구조 확인을 통과했다는 사실만으로 업무 내용의 정확성이나 출처가 검증된 것은 아니다. `sources`는 호출자가 제공한 `Source(document_id, document_version_id, version_no, location)`를 결과에 보존하며, 각 기능이 답변·인용과의 관계를 확인한 뒤 자신의 결과 스키마에 포함한다. `to_api_dict()`가 보장하는 공통 봉투에는 sources를 자동 삽입하지 않는다.

## 키·사용량·오류

- `context.request_key`는 분석 요청을 식별한다. `call_keys`는 **각 실제 모델 호출에 사용할 서로 다른 키**이며 호출자가 여러 요청과 프로세스를 통틀어 유일성을 보장한다. 재시도 횟수만큼 서로 다른 키가 필요하다. 동일 호출의 usage 전달 재시도만 기존 키를 재사용한다.
- 검증은 `llm-validate-{validation_id}-attempt-{n}-call-{k}`를 사용하고 `max_attempts=1`이다. 명시적 재시도는 BE가 새 attempt로 접수한다. 일반 기능도 재시도 횟수와 전체 시간 예산을 명시해야 한다.
- 자동 재시도가 허용된 호출에서 공급자의 429 또는 503 응답에 유효한 `Retry-After`가 있으면 기본 대기 시간과 이 값 중 더 긴 시간만큼 기다린다. 대기 시간이 남은 전체 시간 예산 이상이면 추가 호출 없이 `model_timeout`으로 끝내고 기존 사용량을 보존한다. 검증 호출은 자동 재시도하지 않는다.
- 성공 결과와 `LLMError.usage`는 해당 요청의 실제 호출별 usage를 보존한다. 실패한 호출도 기록하며, 호출이 시작되지 않은 입력·설정 오류에는 usage를 만들지 않는다. 전송 중 시간 초과로 응답을 받지 못하면 토큰은 미확인이다.
- 실행 취소는 원래 `asyncio.CancelledError`에 `llm_usage`를 담아 그대로 전파한다. 호출자가 기록을 처리하더라도 취소를 다시 전파해야 한다. 기존 `LLMCancelledError` 심볼은 호환을 위해 남아 있지만 클라이언트가 더 이상 발생시키지 않으므로 해당 예외와 `.usage`를 사용하는 호출자는 이전해야 한다. 네트워크 단절이나 외부 timeout으로 AI 응답 자체가 전달되지 않으면 BE가 호출·사용량 미확인을 별도로 기록하며, 정확히 한 번의 모델 호출을 보장하지 않는다.
- 토큰을 둘 다 확인할 수 없는 경우 `tokens_known=false`로 표시하고 두 값을 생략한다. 알려진 0은 유지한다. 가격 설정 또는 공급자가 제공한 비용 정보가 없으면 비용·통화를 함께 생략한다. `Pricing`은 호출자가 명시적으로 전달한 단가를 사용하며 기본 가격표가 없다.
- V2의 `llm_usage` 저장 범위에 맞춰 토큰은 32비트 정수 범위, 비용은 `NUMERIC(12,6)`을 따른다. 비용은 소수 여섯 자리로 반올림하며 범위를 넘거나 양수 비용이 0으로 반올림되는 경우 비용·통화를 생략하고 usage는 보존한다.
- 호출자가 제공한 단가로 추정할 때는 실제 응답 모델이 요청 모델과 일치하는 경우만 계산한다. 허용된 별칭이라도 다르면 부정확한 가격 추정을 피하기 위해 비용을 생략한다.
- `LLMUsage`는 프로젝트·선택 작업/검증 ID와 attempt도 보존한다. `to_api_dict()`는 공통 Usage DTO 필드를 출력하며 BE는 원래 요청 맥락을 함께 사용해 저장한다. 서버가 실제 모델 호출 여부를 확인할 수 없는 경우 이를 별도로 미확인 처리해야 한다.
- 현재 공통 Usage DTO에는 `provider`가 없지만 V2 `llm_usage.provider`는 필수다. BE가 신뢰할 수 있는 제공자 ID를 어떤 설정·요청 맥락에서 결정할지 BE 담당자와 합의가 필요하다. 모델명이나 임의 응답만으로 공급자를 확정하지 않는다. V2의 소문자 `origin`(`model`, `template`, `model_failed_template`)과 AI 봉투의 대문자 `MODEL`/`TEMPLATE`를 BE에서 매핑하는 방식도 함께 정한다.
- 공통 예외는 안전한 `code`와 사용량을 제공한다. 각 API는 자신의 계약에 따라 HTTP 응답 코드로 매핑하며, 실패를 빈 성공이나 자동 PASS로 바꾸지 않는다. 프롬프트·자격 증명·제공자 응답 본문을 예외 메시지나 로그에 넣지 않는다.

### 외부 시간 제한과 사용량

`generate()`의 자체 시간 제한에 걸리면 `LLMError`의 `code`는 `model_timeout`이며 `error.usage`에서 호출별 기록을 확인한다. `asyncio.timeout()` 또는 `asyncio.wait_for()`를 바깥에 적용하면 표준 동작에 따라 `TimeoutError`가 발생한다. 같은 프로세스의 호출자는 예외의 원인인 `CancelledError.llm_usage`에서 확인된 기록을 얻을 수 있다.

```python
import asyncio

from app.services.llm_client import LLMError

try:
    async with asyncio.timeout(budget):
        result = await client.generate(request)
except TimeoutError as exc:
    cause = exc.__cause__
    usage = getattr(cause, "llm_usage", ())
    # usage와 요청 맥락을 기록할 수 있다.
    raise
except asyncio.CancelledError as exc:
    usage = getattr(exc, "llm_usage", ())  # 직접 취소는 처리 후 다시 전파한다.
    raise
except LLMError as exc:
    usage = exc.usage  # generate() 자체 시간 제한과 기타 공통 오류
    raise
```

`asyncio.wait_for(client.generate(request), timeout=budget)`도 같은 방식으로 시간 초과와 사용량을 처리한다. 원인이나 사용량이 없다고 호출이 없었다고 단정할 수 없다. 토큰을 받기 전에 취소되면 사용량의 토큰은 미확인이다. 프로세스 종료나 네트워크 단절로 AI 응답이 BE에 도착하지 않은 경우 이 예외만으로 BE가 사용량을 복구할 수는 없다.

기능 담당자가 공통 응답 수신 후 직접 작성한 업무 검증에 실패한 경우에는 `raise response.invalid_output_error() from None`으로 실제 호출 usage를 보존하면서 마지막 호출을 실패로 표시할 수 있다. 여러 번의 독립 호출을 합쳐 검증하는 기능은 앞선 호출의 usage를 `previous_usage`로 전달한다. 검증 내용과 HTTP 응답 판정은 각 기능의 책임이다.

계약 기준: [공통 Usage·Source](../../../../docs/api/00-common.md), [AI 내부 API](../../../../docs/api/03-ai-internal.md), [107 검증 예시](../../../../docs/api/examples/validation/README.md).
