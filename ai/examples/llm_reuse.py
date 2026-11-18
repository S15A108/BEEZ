"""공통 LLM 클라이언트를 다른 AI 기능에서 재사용하는 실행 가능한 예제.

외부 모델 호출·API 키 없이 `python -m examples.llm_reuse`로 실행한다.
실제 챗봇 모듈과 모델 설정은 담당자가 연결하며, 이 예제의 응답은 합성 데이터다.
"""

import asyncio
import json

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMMessage,
    LLMRequest,
    ProviderResponse,
    Source,
)


class ExampleProvider:
    """실제 공급자 대신 사용해 공통 호출과 usage 전달을 확인한다."""

    async def complete(
        self, request: LLMRequest, *, timeout_seconds: float
    ) -> ProviderResponse:
        return ProviderResponse(
            content='{"answer":"로그인 계약의 응답 형식을 확인하세요."}',
            model=request.model,
            input_tokens=12,
            output_tokens=8,
        )


async def answer_for_bee(client: LLMClient) -> None:
    """작업이 없는 대화 기능의 재사용 예제. 출처는 호출자가 제공한다."""
    request = LLMRequest(
        model="example-model",
        messages=(LLMMessage(role="user", content="로그인 계약을 알려줘"),),
        context=LLMContext(
            project_id="101", feature="BEE_CHAT", request_key="example-chat-request"
        ),
        call_keys=("example-chat-request-call-1",),
        timeout_seconds=5.0,
        response_format={"type": "json_object"},
        sources=(
            Source(
                document_id="201",
                document_version_id="211",
                version_no=1,
                location="인증 API / 로그인",
            ),
        ),
    )
    result = await client.generate(request)
    print(json.dumps(result.to_api_dict(), ensure_ascii=False))
    print("호출자가 전달한 출처:", result.sources)


async def validate_plan(client: LLMClient) -> None:
    """작업·검증 ID가 있는 호출 예제. 검증 판정은 BE가 담당한다."""
    request = LLMRequest(
        model="example-model",
        messages=(LLMMessage(role="user", content="로그인 계획을 분석해줘"),),
        context=LLMContext(
            project_id="101",
            feature="TASK_VALIDATION",
            request_key="example-validation-request",
            task_id="501",
            validation_id="601",
            attempt=1,
        ),
        call_keys=("llm-validate-601-attempt-1-call-1",),
        timeout_seconds=5.0,
        response_format={"type": "json_object"},
    )
    result = await client.generate(request)
    print(json.dumps(result.to_api_dict(), ensure_ascii=False))


async def main() -> None:
    client = LLMClient(ExampleProvider())
    await answer_for_bee(client)
    await validate_plan(client)


if __name__ == "__main__":
    asyncio.run(main())
