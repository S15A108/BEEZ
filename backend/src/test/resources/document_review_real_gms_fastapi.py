"""Opt-in local FastAPI server using the real provider; never log secrets."""
import os
import sys

import uvicorn
from fastapi.responses import PlainTextResponse
from pydantic import SecretStr

import app.main as main_module
from app.core.config import Settings, get_settings

settings = Settings(ai_internal_token=SecretStr(os.environ["BEEZ_REVIEW_E2E_TOKEN"]))
if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
    raise SystemExit("GMS key is not configured")

# The unrelated retrieval vocabulary is not needed for document review.
main_module.EmbeddingTokenCounter = lambda: None
main_module.app.dependency_overrides[get_settings] = lambda: settings


@main_module.app.get("/__e2e_ready", include_in_schema=False)
async def ready():
    return PlainTextResponse(os.environ["BEEZ_REVIEW_E2E_NONCE"])


if __name__ == "__main__":
    uvicorn.run(
        main_module.app,
        host="127.0.0.1",
        port=int(sys.argv[1]),
        log_level="critical",
        access_log=False,
    )
