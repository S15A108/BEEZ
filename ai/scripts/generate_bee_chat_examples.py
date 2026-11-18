"""Regenerate docs/api/examples/bee-chat/*.json and the dry-run report (#135).

Uses the scripted fake provider and the scripted judge only; no network or
model call is made.
"""

import asyncio
import json
from pathlib import Path

from evaluations.bee_chat.examples import build_examples
from evaluations.bee_chat.run import (
    DRY_TITLE,
    ScriptedAnswerProposer,
    ScriptedClaimVerifier,
    evaluate,
    render_markdown,
)

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs" / "api" / "examples" / "bee-chat"
REPORT = ROOT / "ai" / "evaluations" / "bee_chat" / "results-2026-11-03-dry.md"


def main() -> None:
    EXAMPLES.mkdir(parents=True, exist_ok=True)
    for name, payload in build_examples().items():
        (EXAMPLES / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"wrote {EXAMPLES / name}")
    report = asyncio.run(evaluate(ScriptedAnswerProposer, ScriptedClaimVerifier))
    REPORT.write_text(render_markdown(report, title=DRY_TITLE), encoding="utf-8")
    print(f"wrote {REPORT}")


if __name__ == "__main__":
    main()
