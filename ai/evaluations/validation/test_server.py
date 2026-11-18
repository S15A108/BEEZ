"""Local-only test server injecting the real OpenAI provider into AI-A02.

Only for isolated, synthetic evaluation. This does not change or test the
application's configured production GMS provider. Calls incur API costs.
"""

import argparse
import os
from contextlib import asynccontextmanager
from types import SimpleNamespace

import uvicorn
from fastapi import FastAPI, Request
from pydantic import SecretStr, ValidationError

from app.core.config import get_settings
from app.routers import validation as validation_router
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, OpenAICompatibleProvider
from evaluations.validation.run import configuration


def create_test_app() -> FastAPI:
    """Build a standalone test server, never changing production app settings."""
    token = os.environ.get("BEEZ_VALIDATION_LIVE_AI_TOKEN", "")
    if len(token) < 16:
        raise ValueError("BEEZ_VALIDATION_LIVE_AI_TOKEN must be at least 16 characters")
    if os.environ.get("VALIDATION_EVAL_KEY_NAME") != "OPENAI_API_KEY":
        raise ValueError("Only the OpenAI key is supported in this test server")
    endpoint, model, key = configuration()
    if endpoint != "https://api.openai.com/v1/chat/completions":
        raise ValueError("The OpenAI Chat Completions endpoint is required")
    response_format = os.environ.get("VALIDATION_EVAL_RESPONSE_FORMAT", "json_object")
    if response_format not in {"json_object", "json_schema"}:
        raise ValueError("Unsupported evaluation output format")

    settings = SimpleNamespace(
        ai_internal_token=SecretStr(token),
        bee_default_model=model,
        validation_response_format=response_format,
    )
    app = FastAPI(
        title="BEEZ synthetic validation test server", docs_url=None, redoc_url=None
    )
    app.dependency_overrides[get_settings] = lambda: settings

    @app.middleware("http")
    async def inspect_synthetic_request(request: Request, call_next):
        if request.method == "POST" and request.url.path == "/v1/validate":
            try:
                # Request.body() caches bytes; call_next receives the cached body.
                ValidateRequest.model_validate_json(await request.body())
                print("synthetic request schema: valid", flush=True)
            except ValidationError as error:
                for item in error.errors(include_url=False, include_input=False):
                    print(
                        "synthetic request schema:",
                        "loc=",
                        item.get("loc"),
                        "type=",
                        item.get("type"),
                        flush=True,
                    )
        return await call_next(request)

    @asynccontextmanager
    async def injected_client(_settings):
        async with OpenAICompatibleProvider(endpoint=endpoint, api_key=key) as provider:
            yield LLMClient(provider)

    validation_router.get_validation_client = injected_client
    app.include_router(validation_router.router)
    return app


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Local-only synthetic validation integration test server"
    )
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    uvicorn.run(
        create_test_app(),
        host="127.0.0.1",
        port=args.port,
        log_level="warning",
        access_log=False,
    )


if __name__ == "__main__":
    main()
