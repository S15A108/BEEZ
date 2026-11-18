"""Exercise only the isolated cdc_demo outbox; requires Python 3 and Docker Compose."""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent
CONNECT_URL = "http://127.0.0.1:18083"
CONNECTOR = "beez-cdc-poc-outbox"
TOPIC = "beez_cdc.cdc_demo.publication_outbox"


def request(path, method="GET", value=None):
    data = None if value is None else json.dumps(value).encode("utf-8")
    req = urllib.request.Request(
        CONNECT_URL + path, data=data, method=method,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def docker(*args, timeout=60):
    return subprocess.run(
        ["docker", "compose", *args], cwd=ROOT, text=True, encoding="utf-8",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
    )


def psql(sql):
    result = docker("exec", "-T", "postgres", "psql", "-U", "cdc_poc", "-d", "cdc_poc",
                    "-v", "ON_ERROR_STOP=1", "-At", "-c", sql)
    if result.returncode:
        raise RuntimeError("PostgreSQL example failed: " + result.stderr)
    return result.stdout


def register():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            request("/")
            break
        except (OSError, urllib.error.URLError):
            time.sleep(2)
    else:
        raise RuntimeError("Debezium Connect did not become ready within 120 seconds")

    with (ROOT / "connector.json").open(encoding="utf-8-sig") as source:
        config = json.load(source)["config"]
    deadline = time.monotonic() + 90
    registered = False
    while time.monotonic() < deadline:
        try:
            if not registered:
                request("/connectors/" + CONNECTOR + "/config", "PUT", config)
                registered = True
            status = request("/connectors/" + CONNECTOR + "/status")
        except urllib.error.HTTPError as exc:
            # Registration/task assignment can briefly be unavailable on startup.
            if exc.code in (404, 409, 503):
                time.sleep(2)
                continue
            raise
        tasks = status.get("tasks", [])
        if status["connector"]["state"] == "FAILED" or any(t["state"] == "FAILED" for t in tasks):
            raise RuntimeError("Connector failed: " + json.dumps(status, ensure_ascii=False))
        if status["connector"]["state"] == "RUNNING" and tasks and all(t["state"] == "RUNNING" for t in tasks):
            active = psql("SELECT count(*) FROM pg_replication_slots "
                          "WHERE slot_name = 'beez_cdc_poc' AND active;").strip()
            if active == "1":
                return
        time.sleep(2)
    raise RuntimeError("Connector task or logical replication slot did not become active")


def test():
    register()
    committed = str(uuid.uuid4())
    rolled_back = str(uuid.uuid4())
    sentinel = str(uuid.uuid4())
    baseline_id = str(int(committed.replace("-", "")[:12], 16) + 1)
    rollback_baseline_id = str(int(rolled_back.replace("-", "")[:12], 16) + 1)
    sentinel_baseline_id = str(int(sentinel.replace("-", "")[:12], 16) + 1)
    event = {
        "event_id": committed,
        "event_type": "DESIGN_PUBLISHED",
        "event_version": 1,
        "publication_kind": "CHANGE",
        "project_id": "10",
        "baseline_id": baseline_id,
        "previous_baseline_id": "101",
        "change_proposal_id": "201",
        "occurred_at": "2026-01-01T00:00:00Z",
        "changed_items": [{
            "design_item_id": "301",
            "change_type": "UPDATED",
            "previous_version_id": "401",
            "published_version_id": "402",
        }],
    }
    payload = json.dumps(event, ensure_ascii=True, separators=(",", ":"))
    rollback_event = {**event, "event_id": rolled_back, "baseline_id": rollback_baseline_id}
    rollback_payload = json.dumps(rollback_event, ensure_ascii=True, separators=(",", ":"))
    sentinel_event = {**event, "event_id": sentinel, "baseline_id": sentinel_baseline_id}
    sentinel_payload = json.dumps(sentinel_event, ensure_ascii=True, separators=(",", ":"))
    sql = f"""
BEGIN;
INSERT INTO cdc_demo.baselines VALUES ({baseline_id}, 10, 'committed example');
INSERT INTO cdc_demo.publication_outbox
(event_id, event_type, event_version, project_id, baseline_id, occurred_at, payload)
VALUES ('{committed}', 'DESIGN_PUBLISHED', 1, 10, {baseline_id}, '2026-01-01T00:00:00Z', '{payload}'::jsonb);
COMMIT;
BEGIN;
INSERT INTO cdc_demo.baselines VALUES ({rollback_baseline_id}, 10, 'rolled back example');
INSERT INTO cdc_demo.publication_outbox
(event_id, event_type, event_version, project_id, baseline_id, occurred_at, payload)
VALUES ('{rolled_back}', 'DESIGN_PUBLISHED', 1, 10, {rollback_baseline_id}, '2026-01-01T00:00:00Z', '{rollback_payload}'::jsonb);
ROLLBACK;
BEGIN;
INSERT INTO cdc_demo.baselines VALUES ({sentinel_baseline_id}, 10, 'sentinel example');
INSERT INTO cdc_demo.publication_outbox
(event_id, event_type, event_version, project_id, baseline_id, occurred_at, payload)
VALUES ('{sentinel}', 'DESIGN_PUBLISHED', 1, 10, {sentinel_baseline_id}, '2026-01-01T00:00:00Z', '{sentinel_payload}'::jsonb);
COMMIT;
"""
    psql(sql)
    # A later committed sentinel on this one-partition POC topic proves that the
    # reader advanced past the rollback in this run. It does not prove future
    # exactly-once delivery or production ordering.
    deadline = time.monotonic() + 90
    records = []
    while time.monotonic() < deadline:
        consumed = docker(
            "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
            "--bootstrap-server", "kafka:9092", "--topic", TOPIC,
            "--from-beginning", "--timeout-ms", "6000", timeout=20,
        )
        records = []
        for line in consumed.stdout.splitlines():
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            if item and isinstance(item, dict):
                records.append(item)
        ids = [(r.get("after") or {}).get("event_id") for r in records]
        if committed in ids and sentinel in ids:
            break
        time.sleep(2)
    committed_records = [
        r for r in records if (r.get("after") or {}).get("event_id") == committed
    ]
    sentinel_records = [
        r for r in records if (r.get("after") or {}).get("event_id") == sentinel
    ]
    rollback_records = [
        r for r in records if (r.get("after") or {}).get("event_id") == rolled_back
    ]
    if len(committed_records) != 1 or committed_records[0].get("op") != "c":
        raise AssertionError("Expected one committed CDC INSERT, got: " + repr(committed_records))
    if len(sentinel_records) != 1 or sentinel_records[0].get("op") != "c":
        raise AssertionError("Expected a later committed sentinel, got: " + repr(sentinel_records))
    ids = [(r.get("after") or {}).get("event_id") for r in records]
    if committed not in ids or sentinel not in ids or ids.index(committed) >= ids.index(sentinel):
        raise AssertionError("Expected committed event before sentinel on one-partition test topic")
    if rollback_records:
        raise AssertionError("Rolled-back outbox unexpectedly reached Kafka")
    actual_payload = committed_records[0]["after"]["payload"]
    if isinstance(actual_payload, str):
        actual_payload = json.loads(actual_payload)
    if actual_payload != event:
        raise AssertionError("Outbox payload changed: " + repr(actual_payload))
    print("PASS: committed DESIGN_PUBLISHED outbox INSERT reached Kafka with intact payload")
    print("PASS: rolled-back outbox INSERT was not observed before the later committed sentinel")
    print("event_id:", committed)
    print("topic:", TOPIC)


if __name__ == "__main__":
    try:
        test()
    except (OSError, RuntimeError, AssertionError, subprocess.TimeoutExpired) as exc:
        print("CDC smoke test failed:", exc, file=sys.stderr)
        sys.exit(1)
