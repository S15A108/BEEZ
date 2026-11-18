# 229 isolated fault and notification verification

This runner exercises the existing application JAR, PostgreSQL schema and DB metrics reader,
Kafka listener, Debezium connector, Kafka/Postgres exporters, production Prometheus rules and
Alertmanager receiver. It sends clearly marked **`[229 격리 검증]`** messages to the existing
Mattermost webhook supplied in a private file. No webhook address is committed or printed.

All destructive fault operations target Compose project `beez-cdc-monitoring-229`, never the
development service. Ports are bound only to localhost: backend `18229`, Connect `18230`,
PostgreSQL `15229`, Prometheus `19229`, Alertmanager `19230`. Runtime configuration and raw
observations are ignored by Git. `prepare.py` refuses to overwrite an existing project's files.

## Run

Run from the repository root on Windows with JDK 21, Docker Desktop/Compose, Python 3 and the
Gradle dependencies available. The monitoring scripts otherwise use only the Python standard library.
Generate the companion fixture explicitly; its export is disabled by default:

```powershell
New-Item -ItemType Directory -Force infra/cdc-poc/monitoring-229/.runtime | Out-Null
$fixturePath = Join-Path (Get-Location) 'infra/cdc-poc/monitoring-229/.runtime/publication-fixture.sql'
$env:BEEZ_229_EXPORT_FIXTURE = $fixturePath
python infra/cdc-poc/recovery-229/run.py
if ($LASTEXITCODE -ne 0) { throw 'Companion recovery validation failed' }
$env:BEEZ_229_PUBLICATION_FIXTURE = $fixturePath
```

The [companion runner](../recovery-229/README.md) builds this checkout's JAR and exports only
synthetic records. Do not substitute a shared or production database export.

The run above produces `backend/build/libs/beez-backend-0.0.1-SNAPSHOT.jar`. Download the official JMX exporter
1.6.0 to `.runtime/jmx-agent.jar`; its SHA256 must be
`a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e`.
The file is available from the [official 1.6.0 release](https://github.com/prometheus/jmx_exporter/releases/tag/1.6.0).

```powershell
$env:BEEZ_229_MATTERMOST_WEBHOOK_FILE = '<private existing webhook file>'
# BEEZ_229_PUBLICATION_FIXTURE points to the successful companion export above.
python infra/cdc-poc/monitoring-229/prepare.py
docker compose -p beez-cdc-monitoring-229 -f infra/cdc-poc/monitoring-229/.runtime/compose.json up -d
python -u infra/cdc-poc/monitoring-229/control.py all
python infra/cdc-poc/monitoring-229/summarize.py
```

Observe actual channel messages and record their links separately. Receiver HTTP-success counters
alone do not prove that a human can see the intended message. On completion, verify the expected
firing and resolved assertions before removing **only this project** with `docker compose ... down`.
The [229 verification report](../../../docs/verification/cdc-recovery-S15P31A108-229.md) records
the actual Mattermost receipt links alongside the broader backend/frontend validation.
The exact scoped cleanup command is:

```powershell
docker compose -p beez-cdc-monitoring-229 -f infra/cdc-poc/monitoring-229/.runtime/compose.json down --volumes
```

This removes only the test project's containers, network and attached anonymous volumes. PostgreSQL
uses a disposable tmpfs. A subsequent prepare archives the preceding run's events and provenance in
the ignored runtime directory, so `results.json` cannot mix multiple runs.

## Scope and timing

- The initial persisted-state fixtures produce a fresh PENDING row blocked behind a FAILED predecessor,
  an expired RUNNING row and a stored exhausted-retry state. The real app's metrics queries observe
  those rows. They do not themselves execute a model retry or kill a worker; separate backend recovery
  tests cover those behaviors.
- A malformed record really blocks the production Kafka listener. A connector pointed at an incompatible
  physical slot really fails its task. Both are restored after observing their actual alerts.
- Kafka `pause` and `stop` each precede a real backend restart; backend health, recovery scans and automatic
  Kafka reconnection are checked. The companion recovery test exports a coherent synthetic publication
  graph. After importing it, the runner creates and approves **new** proposals over the authenticated
  backend HTTP API while Kafka is unavailable, then requires durable `COMPLETED` and exactly one attempt.
  After restoration, an independent consumer verifies their actual Kafka create/completion records;
  the application's original group's committed offsets must reach the log end, exporter lag must be zero,
  and both database rows must still have exactly one completed attempt.
- Connect stop/restart and isolated connector deletion/recreation exercise their alerts and recovery.
  Restoration runs in `finally` blocks even when an assertion fails.
- During the Connect-stop interval, temporarily renaming a test schema table makes the actual metrics
  query fail. A `finally` block restores the table; `CdcSnapshotFailing` must fire and resolve. This runs
  after prior state alerts have had time to resolve, avoiding their suppression during aggregation failure.
- Production alert expressions, `for`, `keep_firing_for`, grouping and repeat intervals stay unchanged.
  The PENDING row is not backdated: its 600-second age plus 5-minute hold is observed in real elapsed time.
  The 24-hour FAILED repeat route is inspected, but this run does not wait 24 hours.
- `ai_fixture.py` is an explicitly deterministic HTTP model boundary, with no external model call. Actual
  GMS execution and semantic outcomes are verified in the separately recorded backend tests.

`results.json` is generated from timestamped observations and explicit assertions; a script merely
finishing a sleep is not a passing result. Resolution additionally requires backend health, fresh
successful DB metrics, healthy source scrapes, reachable Kafka, consumer UP and a RUNNING connector/task.

The recorded 2026-11-10 run stopped at the user's request after twelve alert families had fired and
resolved. The optional connector deletion/recreation (`CdcConnectorMissing`) was cancelled before
injection. `results.json` preserves that exclusion and does not label the complete thirteen-case suite passed.
