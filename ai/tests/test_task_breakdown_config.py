import pytest
from fastapi import HTTPException

from app.core.config import Settings
from app.routers.task_breakdown import (
    TaskBreakdownConfig,
    require_task_breakdown_config,
)


def test_task_breakdown_uses_its_own_environment_limits(monkeypatch):
    monkeypatch.setenv("DESIGN_DRAFTS_LLM_TIMEOUT_SECONDS", "150")
    monkeypatch.setenv("DESIGN_DRAFTS_LLM_OUTPUT_TOKEN_LIMIT", "5000")
    monkeypatch.delenv("TASK_BREAKDOWN_LLM_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("TASK_BREAKDOWN_LLM_OUTPUT_TOKEN_LIMIT", raising=False)
    assert require_task_breakdown_config(
        Settings(_env_file=None)
    ) == TaskBreakdownConfig(75, 16_384)

    monkeypatch.setenv("TASK_BREAKDOWN_LLM_TIMEOUT_SECONDS", "47.5")
    monkeypatch.setenv("TASK_BREAKDOWN_LLM_OUTPUT_TOKEN_LIMIT", "18000")
    assert require_task_breakdown_config(
        Settings(_env_file=None)
    ) == TaskBreakdownConfig(47.5, 18_000)


@pytest.mark.parametrize(
    ("timeout", "tokens"),
    [("0", "100"), ("nan", "100"), ("inf", "100"), ("50", "0"), ("50", "invalid")],
)
def test_task_breakdown_rejects_invalid_limits(timeout, tokens):
    settings = Settings(
        _env_file=None,
        task_breakdown_llm_timeout_seconds=timeout,
        task_breakdown_llm_output_token_limit=tokens,
    )

    with pytest.raises(HTTPException) as failure:
        require_task_breakdown_config(settings)

    assert failure.value.status_code == 503
    assert failure.value.detail == "task_breakdown_not_configured"
