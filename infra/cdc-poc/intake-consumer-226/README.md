# Isolated #226 Spring Kafka consumer integration test

Run from the repository root with Windows, Java 21, Python 3 and Docker Desktop:

```powershell
python infra/cdc-poc/intake-consumer-226/run.py
```

The runner refuses to touch an existing `beez-cdc-intake-consumer-226-test` Compose project. It starts its own Kafka broker on localhost port 29126, creates a uniquely named single-partition topic and group, invokes a Spring Kafka integration test, then removes only that project's containers and network. It does not delete volumes. The port must be available; it uses only local test configuration.

The test starts the real opt-in #226 Spring listener with an explicitly supplied test-only decoder and intake port. A PENDING fixture's first check fails; a retry of the same delivery is deliberately held before completion to verify that its offset has not advanced and a following record has not yet reached the port. It then releases the retry, verifies subsequent progress, and confirms that NOT_EVALUATED and a Kafka tombstone do not trigger intake work. The test stops the same consumer group, publishes while stopped, restarts and checks delivery and committed offsets. After waiting for the group to become empty it rewinds that same group to the initial offset and verifies each pending reference is checked again; an in-memory fixture prevents duplicate simulated work. An unsupported delete event is retried without acknowledgement and blocks the later record.

**Limitations:** The decoder accepts an artificial delimiter format, not the #197 table schema. The port is an in-memory test double and provides no persistent/atomic claim, DB transaction, lease, fencing, result storage, persistent restart durability or analysis execution. Kafka and the actual listener are real; #197 and its durable contract are not. Single broker/partition and local PLAINTEXT do not validate production security, multi-project ordering or loss recovery.
