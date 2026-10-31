"""Evaluation reports must distinguish an empty successful result from failure."""

import asyncio

from app.services.llm_client import LLMError, LLMResult, LLMUsage
from evaluations.change_impact.cases import CASES
from evaluations.change_impact.run import evaluate


class FakeAnalysis:
    def __init__(self):
        self.calls = 0
        self.formats = []

    async def analyze(self, request, config):
        self.calls += 1
        self.formats.append(config.response_format_mode)
        if self.calls == 2:
            raise LLMError("model_unavailable", ())
        return LLMResult(
            result={
                "candidates": [{"task_id": "501"}],
                "evidence_status": "SUFFICIENT",
            },
            model=config.model,
            usage=(
                LLMUsage(
                    request_key=config.call_key,
                    feature="CHANGE_IMPACT",
                    project_id=request.project_id,
                    model=config.model,
                    latency_ms=1,
                    failed=False,
                    tokens_known=False,
                ),
            ),
        )


def test_report_records_success_and_failure_separately():
    service = FakeAnalysis()
    rows = asyncio.run(
        evaluate(service, "test-model", CASES[:2], response_format_mode="json_schema")
    )
    assert service.formats == ["json_schema", "json_schema"]
    assert rows[0]["status"] == "completed"
    assert rows[0]["actual_task_ids"] == ["501"]
    assert rows[0]["target_match"] is True
    assert rows[0]["evidence_match"] is True
    assert rows[0]["usage"][0]["feature"] == "CHANGE_IMPACT"
    assert rows[1]["status"] == "failed"
    assert rows[1]["error_code"] == "model_unavailable"
    assert "actual_task_ids" not in rows[1]
