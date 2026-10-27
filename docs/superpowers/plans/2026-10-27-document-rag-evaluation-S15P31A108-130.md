# #130 AI Evaluation Execution

Continuation authorized by the user's request to finish their AI part. Spec: `docs/experiments/document-parsing-evaluation.md`, RAG development Tasks 4/6 and #130 contract. Work inline on existing #130 branch; keep #129 unchanged and preserve unrelated untracked files.

## Design and scope

Implement a standalone evaluation package, not a production search/chat endpoint. Reuse the real parser/chunker/GMS embedding adapter. Compare fixed token limits vs structure, each with heading/table context off/on, 600 tokens, overlap 0, exact cosine top-12, context at most 6 chunks/4,000 tokens. Record original Unicode source ranges as gold so chunk counts cannot change the denominator. Four synthetic Markdown kinds and twelve questions are a development pilot, not an independent human-reviewed or production benchmark. Preserve negative/no-evidence and document-instruction cases.

C uses controlled event barriers and an independent trace validator for version/content/citation agreement. Its reference backend is explicitly a simulation; export traces and BE acceptance instructions. Never label the simulation as PostgreSQL testing. Actual BE endpoints/DB/migrations and production #92 answering remain owned by their implementation tickets.

Live D uses configured `gpt-6-luna` through GMS Responses and text-embedding-3-small/1024. Same prompt and output cap across all settings, 3 repetitions, no retries, <=200 paid calls and <=500 estimated credits at captured public prices. Cache identical embedding texts within this run, attribute both logical index cost and actual shared calls. Reserve output cost before dispatch. Store usage, elapsed times, raw answers/citations and failures incrementally without credentials. No provider fallback, silent failed-row exclusion or automatic paid reruns. Unit tests never use GMS.

Alternative: waiting for BE would leave AI tooling unfinished; implementing BE storage here would cross ownership. The chosen standalone runner allows useful AI measurements now and later reruns against BE trace/results.

## Task 1 — Corpus, chunk comparison and scoring

- Add four synthetic documents and twelve authored gold questions/ranges. Mark agent-authored labels as awaiting human review.
- RED: fair gold denominators across split/duplicate chunks; wrong version excluded; partial evidence not counted as full recall; unknown citation/false abstention rejected; token budget/source reconstruction of fixed and structure modes.
- GREEN: implement evaluation-only chunk records, exact scoring and deterministic preparation. No runtime app changes.
- Verify targeted tests then full AI suite. Expected: pass, no network calls.

## Task 2 — Controlled C and bounded live D

- RED: stale answer before final validation rejected; change after final validation keeps validated snapshot; content v1 with citation v2 rejected; budget exhaustion blocks dispatch; incomplete/malformed Responses preserved as failures with usage.
- GREEN: event-barrier simulator + independent trace checks; capped GMS runner, dry plan, JSON checkpoint reports and provenance hashes. Reuse embeddings validation.
- Verify targeted tests and full suite; capture dry-run budget before paid execution. Expected: 3 repetitions × 4 settings × 12 questions = 144 generation rows, plus bounded document/query embedding batches.

## Task 3 — Run, review, handoff

- Execute C simulation and D live once under the fixed budget. Inspect all errors/answers; retain failures in totals. Record cost/latency distributions and numerator/denominator, not just averages.
- Write results, reproducible commands, provisional setting decision and exact BE/human-review blockers in `docs/experiments/`. No unsupported quality/DB readiness claims.
- Verify Ruff, complete AI tests and diff checks. Request one independent review; if unavailable disclose it rather than claim review passed.
- Commit with S15P31A108-130 and push only the #130 feature branch. Keep #130 in progress; don't merge #21 or create #130 PR before its prerequisite merge.

## Review focus

Source-range unions across split chunks; context counted in token limits but not gold evidence; zero-denominator metrics; failures and unknown usage in cost totals; response truncation/refusal; budget before calls; snapshot after-final semantics; evaluation surrogate scores vs human semantic judgement.

## Ledger

- Baseline: #130 at 75648d5; 156 tests passed with Windows session loop option, Ruff clean. Existing default Windows loop has documented intermittent WinError 10014; use session scope for reliable execution and retain Linux defaults.
- Choice: reuse existing development-evaluation design; this pilot's labels are agent-authored and require human review. Production acceptance and actual BE/DB C remain pending.
- Task 1/2: 10 new tests RED→GREEN; full suite 166 passed, Ruff clean. Execution code fixed at 8c5fb8c for measurement provenance.
- Independent review of 8c5fb8c found malformed Responses envelope handling and a trace oracle trusting its own expected values. Both reproduced as failing tests and fixed; independent baseline→version/value mapping now governs C. Valid usage on HTTP failures was regraded as necessary cost-accounting correctness and fixed as well.
- Live interruption: Windows transient file sharing denied atomic replace after 76 generated answers. Original checkpoint copied outside repository for audit. Added bounded local file-replace retry and explicit resume that reconstructs scores from saved raw responses, skips every existing generation row, carries prior calls/cost reservations and re-embeds the shared pool with actual extra calls recorded. Prompt, corpus, limits and public prices must match. No automatic paid retry.
- Final fix pass: 8 regression cases RED→GREEN; full suite 174 passed in 4.87s, Ruff check/format clean. An earlier run hit the documented Windows socketpair WinError 10014 in three TestClient setups and the handoff subprocess; unchanged rerun passed. Linux CI settings remain unchanged.
- Review boundaries retained: live answers/metrics reviewed separately by implementer; real BE/DB and independent human semantics remain pending. No statistical superiority or production readiness conclusion from a four-document development pilot.
- Live D complete: 144 unique generation rows (12 questions ×4 settings ×3 repetitions), 150 physical paid calls including the re-embedded pool, no HTTP/schema failures, 290.7104 public-price estimated credits. Every stored score recomputed from saved outputs with no mismatches. Agent read all 144 answer texts; independent human label/semantic review remains pending.
- Findings retained in results: API header absent from final top-6 context under structure modes, strict source-quote span formatting failures and insufficient/answered status/citation contract mismatches. No paid prompt tuning or dataset-driven rerun. Current production defaults preserved provisionally, not declared superior.
- Final profile-resume guard RED→GREEN. Final full suite: 175 passed in 4.83s with Windows Selector policy and session scope in the test process only; Ruff clean (56 Python files). Default Proactor rerun still showed the previously documented WinError 10014 before test bodies; no application/CI policy was changed.
- Status: AI-independent code and development measurement complete; actual BE/DB C/D, joint DTO/state/timeout agreement, human labels and operational document expansion are remaining gates. Jira stays in progress; no merge of #21 and no premature #130 PR.
- Scope superseded by explicit user decision: #130 ends with its AI fixtures/tools, development results, validation, documented BE handoff and dev merge. Previous BE/shared completion gates move to new S15P31A108-213; they are not #130 blockers. Created #213 as a BE Task, unassigned for BE intake, with the original DB/authorization/atomicity/race/timeout requirements retained. PR handoff body is docs/pull-requests/S15P31A108-130.md. Current progress status is pending PR/dev merge only, not pending BE completion.
