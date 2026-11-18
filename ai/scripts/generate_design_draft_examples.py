"""Regenerate docs/api/examples/design-drafts/*.json and the dry-run report (#134).

Uses the scripted fake provider only; no network or model call is made.
"""

import asyncio
import json
from pathlib import Path

from evaluations.design_drafts.examples import build_examples
from evaluations.design_drafts.run import (
    DRY_TITLE,
    ScriptedDraftProposer,
    evaluate,
    render_markdown,
)

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs" / "api" / "examples" / "design-drafts"
REPORT = ROOT / "ai" / "evaluations" / "design_drafts" / "results-2026-11-03-dry.md"


def main() -> None:
    EXAMPLES.mkdir(parents=True, exist_ok=True)
    for name, payload in build_examples().items():
        (EXAMPLES / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"wrote {EXAMPLES / name}")
    row = asyncio.run(evaluate(ScriptedDraftProposer))
    REPORT.write_text(render_markdown(row, title=DRY_TITLE), encoding="utf-8")
    print(f"wrote {REPORT}")


if __name__ == "__main__":
    main()
