"""Regenerate docs/api/examples/planning-questions/*.json and the dry-run report.

Uses the scripted fake provider only; no network or model call is made.
"""

import asyncio
import json
from pathlib import Path

from evaluations.planning_questions.examples import build_examples
from evaluations.planning_questions.run import (
    DRY_TITLE,
    ScriptedProposer,
    evaluate,
    render_markdown,
)

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs" / "api" / "examples" / "planning-questions"
REPORT = (
    ROOT / "ai" / "evaluations" / "planning_questions" / "results-2026-11-02-dry.md"
)


def main() -> None:
    EXAMPLES.mkdir(parents=True, exist_ok=True)
    for name, payload in build_examples().items():
        (EXAMPLES / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"wrote {EXAMPLES / name}")
    rows = asyncio.run(evaluate(ScriptedProposer))
    REPORT.write_text(render_markdown(rows, title=DRY_TITLE), encoding="utf-8")
    print(f"wrote {REPORT}")


if __name__ == "__main__":
    main()
