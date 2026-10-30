# Isolated consumer recovery and replay experiment

Run from the repository root with Windows, Docker Desktop and Java:

    python infra/cdc-poc/consumer-recovery/run.py

The test owns Compose project beez-cdc-consumer-recovery-test and needs local ports
15444, 29095 and 18086. It refuses to start if containers for this project already
exist. On exit it removes only the containers and network it started, never using
-v or deleting existing volumes. It uses test-only credentials and isolated data.

A synthetic source table is captured by a real PostgreSQL logical slot, Debezium
and Kafka. A dedicated Kafka consumer group with auto-commit disabled commits the
first delivered offset. The consumer is then closed while two new rows are committed.
The test waits for the broker end offset and checks positive lag against the group's
committed offset before restarting the same group. It checks that both records are
delivered and commits their offsets.

The test then rewinds the committed offset to the beginning, receives the same topic,
partition and offsets again, and proves that a unique constraint in the fixture receipt
table stops duplicate receipt rows. Finally it simulates a crash window: the DB receipt
commits but the Kafka offset does not, and on restart the record is redelivered without
a duplicate DB receipt.

This is transport and idempotency experimentation, not #197 integration. The
source/receipt tables and consumer are test fixtures, not the project's intake
schema or production consumer. It does not test LLM execution, transactional
coupling of final analysis results, offset expiry or long-term broker/DB loss,
project-level ordering, TLS, or production recovery. A DB commit and a Kafka
offset commit are not one atomic transaction: replay is expected.
