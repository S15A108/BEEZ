# Isolated same-database capture boundary test

Run from the repository root on Windows with Docker Desktop running:

```powershell
python infra/cdc-poc/snapshot-boundary/run.py
```

The test uses an independent pgvector PostgreSQL, Kafka and Debezium. Spring and Debezium access the **same** database. It checks that the PostgreSQL logical slot is active with a confirmed LSN before scanning, then runs this sequence:

1. B1 is already the current published baseline and has no outbox row.
2. The bootstrap scan observes B1.
3. A project-locked SQL transaction commits B2 and its publication outbox row.
4. The real Debezium connector, Kafka and Spring listener deliver the B2 event to the inbox and queue.
5. Bootstrap initializes the scanned B1 while re-reading current B2 under the project lock. Both baselines have exactly one WAITING job, the inbox contains the B2 event, and the cursor starts at version 1. Repeating initialization does not duplicate the job.

The publication transaction is a SQL fixture, not the production publication API. The test does not run a downstream ontology or impact-analysis worker, prove the durability of downstream effects, or exercise Kafka redelivery. Bootstrap's additional integration tests exercise a locked concurrent transaction, a live event received before initialization, duplicate inbox delivery, candidate validation, completed cursor preservation and late-event reconciliation. If an initialized cursor is missing a scanned baseline, it records a RECONCILE job and blocks claims; explicit recovery only proceeds automatically if affected work was never attempted.

The isolated test demonstrates this specific ordered sequence, not a production-wide exactly-once or no-gap guarantee. Production still needs a durable capture-readiness checkpoint, resumable scan and reconciliation, wiring to the publication API, and worker integration. A running connector or this test alone does not guarantee those properties. `initializeScannedCandidate` requires its caller to establish capture before starting the scan.

The runner refuses to start if its own named Compose project already has containers. It creates only `beez-cdc-boundary-test` resources and removes that project's containers and network on exit; it does not pass `-v` and does not delete existing volumes. It does not read or modify application data. Ports 15443, 19094 and 18085 must be free.
