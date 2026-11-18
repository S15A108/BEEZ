"""Transport-only project ordering test against isolated Kafka/Debezium/PostgreSQL."""
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent
COMPOSE = ["docker", "compose", "-f", str(ROOT / "compose.yaml")]
CONNECT = "http://127.0.0.1:18087"
PROJECTS = list(range(101, 113))
VERSIONS = list(range(1, 5))
PARTITIONS = 3


def run(args, *, input_data=None, timeout=60):
    return subprocess.run(args, input=input_data, text=True, encoding="utf-8", capture_output=True, timeout=timeout, check=True)


def compose(*args, input_data=None, timeout=60):
    return run(COMPOSE + list(args), input_data=input_data, timeout=timeout)


def psql(sql):
    return compose("exec", "-T", "postgres", "psql", "-U", "ordering_local", "-d", "ordering_test", "-v", "ON_ERROR_STOP=1", "-At", input_data=sql).stdout.strip()


def request(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(CONNECT + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        text = response.read()
        return json.loads(text) if text else None


def wait_for(label, function, timeout=120):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = function()
            if last:
                return last
        except (OSError, subprocess.CalledProcessError, urllib.error.URLError) as exc:
            last = str(exc)
        time.sleep(1)
    raise RuntimeError(f"{label} timed out; last={last}")



def cleanup_connector_and_slot(name, slot, registration_confirmed):
    """Delete only this run's verified connector and its now-inactive test slot.

    Never infer ownership of a connector with different configuration. Connect
    may have persisted a registration even when the PUT response timed out.
    """
    if not re.fullmatch(r"order_[0-9a-f]{12}", slot) or name != "ordering-" + slot.removeprefix("order_"):
        raise ValueError("Invalid generated test connector or slot name")
    endpoint = "/connectors/" + name
    try:
        config = request(endpoint + "/config")
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        config = None
    else:
        if not isinstance(config, dict):
            raise RuntimeError("Connector configuration response is invalid; refusing cleanup")
    if config is None and not registration_confirmed:
        # A timed-out PUT may still complete later. A 404 alone cannot prove
        # the unknown registration did not create a connector or slot.
        raise RuntimeError("Connector registration outcome is unknown; inspect this run's identifiers")
    if config is not None:
        if config.get("slot.name") != slot or config.get("database.dbname") != "ordering_test":
            raise RuntimeError("Connector does not match this run's test slot and database; refusing cleanup")
        try:
            request(endpoint, "DELETE")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise

        def connector_deleted():
            try:
                request(endpoint + "/config")
            except urllib.error.HTTPError as exc:
                if exc.code == 404:
                    return True
                raise
            return False

        wait_for("connector deletion", connector_deleted, timeout=30)

    # The generated name is restricted before embedding it in SQL; do not drop
    # active slots or a same-named slot from a different database/plugin.
    def slot_inactive():
        state = psql("SELECT active FROM pg_replication_slots "
                     f"WHERE slot_name='{slot}' AND slot_type='logical' "
                     "AND database=current_database() AND plugin='pgoutput'")
        return state != "t"

    wait_for("replication slot to become inactive", slot_inactive, timeout=30)
    psql("SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots "
         f"WHERE slot_name='{slot}' AND slot_type='logical' "
         "AND database=current_database() AND plugin='pgoutput' AND NOT active")
    remaining = psql("SELECT count(*) FROM pg_replication_slots "
                     f"WHERE slot_name='{slot}'")
    if remaining != "0":
        raise RuntimeError("The test slot still exists or does not belong to the test database")
    print(f"PASS: removed connector {name} and inactive slot {slot}")


def records(topic, partition):
    result = subprocess.run(COMPOSE + [
        "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
        "--bootstrap-server", "kafka:9092", "--topic", topic,
        "--partition", str(partition), "--offset", "earliest", "--timeout-ms", "2200",
        "--property", "print.key=true", "--property", "key.separator=|",
        "--property", "print.offset=true",
    ], text=True, encoding="utf-8", capture_output=True, timeout=30, check=False)
    if result.returncode:
        classes = re.findall(r"\b(?:[a-z]\w*\.)+[A-Z]\w*(?:Exception|Error)\b", result.stderr)
        if not classes or any(x != "org.apache.kafka.common.errors.TimeoutException" for x in classes):
            raise RuntimeError("Kafka read failed: " + result.stderr.strip())
    found = []
    for line in result.stdout.splitlines():
        match = re.fullmatch(r"Offset:(\d+)\|(.*?)\|(.*)", line)
        if not match:
            raise RuntimeError("Unexpected Kafka output format: " + repr(line[:300]))
        offset, raw_key, raw_value = match.groups()
        found.append((int(offset), raw_key, json.loads(raw_value)))
    return found


def main():
    existing = run(COMPOSE + ["ps", "--all", "--quiet"])
    if existing.stdout.strip():
        raise RuntimeError("Ordering test project already has containers; refusing to alter it")
    suffix = uuid.uuid4().hex[:12]
    name = "ordering-" + suffix
    slot = "order_" + suffix
    prefix = "order_" + suffix
    topic = prefix + ".public.ordering_fixture"
    run_id = "run_" + suffix
    print(f"Test resources: connector={name}, slot={slot}", flush=True)
    started = False
    registration_attempted = False
    registration_confirmed = False
    try:
        started = True
        compose("up", "-d", timeout=180)
        wait_for("database", lambda: psql("SELECT 1") == "1")
        wait_for("Connect", lambda: request("/").get("version"))
        psql("""
          CREATE TABLE IF NOT EXISTS public.ordering_fixture (
              id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
              run_id TEXT NOT NULL,
              project_id BIGINT NOT NULL,
              version_no INT NOT NULL
          );
          DO $$ BEGIN
              IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname='ordering_fixture_publication') THEN
                  CREATE PUBLICATION ordering_fixture_publication FOR TABLE public.ordering_fixture;
              END IF;
          END $$;
        """)
        compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "kafka:9092",
                "--create", "--topic", topic, "--partitions", str(PARTITIONS), "--replication-factor", "1",
                "--config", "cleanup.policy=delete")
        description = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "kafka:9092", "--describe", "--topic", topic).stdout
        if not re.search(r"PartitionCount:\s*3\b", description):
            raise AssertionError("Actual topic partition count is not three: " + description)
        config = {
            "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
            "plugin.name": "pgoutput", "database.hostname": "postgres", "database.port": "5432",
            "database.dbname": "ordering_test", "database.user": "ordering_local",
            "database.password": "ordering_local_only", "slot.name": slot,
            "publication.name": "ordering_fixture_publication", "publication.autocreate.mode": "disabled",
            "topic.prefix": prefix, "table.include.list": "public.ordering_fixture",
            "message.key.columns": "public.ordering_fixture:project_id", "snapshot.mode": "initial",
            "key.converter": "org.apache.kafka.connect.json.JsonConverter", "key.converter.schemas.enable": "false",
            "value.converter": "org.apache.kafka.connect.json.JsonConverter", "value.converter.schemas.enable": "false",
        }
        # Do not adopt an existing connector or slot, even on the unlikely
        # chance a generated identifier collides with a retained run.
        if psql(f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}'") != "0":
            raise RuntimeError("Generated test slot already exists; refusing to reuse it")
        try:
            request("/connectors/" + name + "/config")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise
        else:
            raise RuntimeError("Generated test connector already exists; refusing to reuse it")
        registration_attempted = True
        request("/connectors/" + name + "/config", "PUT", config)
        registration_confirmed = True

        def running():
            status = request("/connectors/" + name + "/status")
            if status["connector"]["state"] == "FAILED" or any(t["state"] == "FAILED" for t in status.get("tasks", [])):
                raise RuntimeError("Connector failure: " + json.dumps(status))
            return status.get("tasks") and all(t["state"] == "RUNNING" for t in status["tasks"])

        wait_for("connector", running)
        wait_for("active slot", lambda: psql(f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}' AND active") == "1")
        values = ",".join(f"('{run_id}',{project},{version})" for version in VERSIONS for project in PROJECTS)
        psql("INSERT INTO public.ordering_fixture(run_id,project_id,version_no) VALUES " + values + ";")

        def all_test_records():
            all_rows = []
            for partition in range(PARTITIONS):
                for offset, raw_key, event in records(topic, partition):
                    after = event.get("after") or {}
                    if after.get("run_id") == run_id:
                        all_rows.append((partition, offset, raw_key, event))
            return all_rows

        rows = wait_for("all fixture events", lambda: (lambda r: r if len(r) >= len(PROJECTS) * len(VERSIONS) else None)(all_test_records()), timeout=120)
        by_project = {project: [] for project in PROJECTS}
        for partition, offset, raw_key, event in rows:
            after = event["after"]
            project, version = after["project_id"], after["version_no"]
            if event.get("op") != "c" or project not in by_project:
                raise AssertionError("Unexpected CDC operation or project")
            if raw_key != json.dumps({"project_id": project}, separators=(",", ":")):
                raise AssertionError("Unexpected raw serialized key: " + raw_key)
            by_project[project].append((partition, offset, version))
        used = set()
        for project, events in by_project.items():
            parts = {entry[0] for entry in events}
            if len(parts) != 1:
                raise AssertionError(f"Project {project} crossed partitions: {events}")
            used.update(parts)
            if [entry[2] for entry in events] != VERSIONS:
                raise AssertionError(f"Project {project} versions were not in order: {events}")
            if [entry[1] for entry in events] != sorted(set(entry[1] for entry in events)):
                raise AssertionError(f"Offsets were not strictly increasing: {events}")
        if len(used) < 2:
            raise AssertionError("Test did not exercise more than one partition")
        print("PASS: precreated topic reports 3 partitions; Debezium emitted exact project_id JSON keys")
        print("PASS: all 12 projects mapped to one partition each and versions 1,2,3,4 arrived in partition-offset order")
        print("Observed project -> partition/offsets:", {p: [(part, off) for part, off, _ in entries] for p, entries in by_project.items()})
        print("LIMIT: isolated insert fixture; not #197 schema, replay, production config or asynchronous completion ordering")
    finally:
        if started:
            cleanup_error = None
            try:
                if registration_attempted:
                    cleanup_connector_and_slot(name, slot, registration_confirmed)
            except Exception as exc:
                cleanup_error = exc
            try:
                # Only the exclusively owned project; never remove volumes.
                subprocess.run(COMPOSE + ["down"], timeout=90, check=True)
            except (OSError, subprocess.SubprocessError) as exc:
                if cleanup_error is None:
                    cleanup_error = exc
                else:
                    print("Compose cleanup also failed:", exc, file=sys.stderr)
            if cleanup_error is not None:
                raise RuntimeError(f"Test cleanup failed; inspect {name}/{slot} before retrying: {cleanup_error}") from cleanup_error


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("Project ordering CDC experiment failed:", exc, file=sys.stderr)
        if isinstance(exc, subprocess.CalledProcessError) and exc.stderr:
            print(exc.stderr.strip(), file=sys.stderr)
        sys.exit(1)
