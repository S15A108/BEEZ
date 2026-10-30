"""Isolated #225 CDC experiment. No project database or application worker is used."""

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
CONNECT = "http://127.0.0.1:18084"
SECRET = "PRIVATE_POC_BODY_STORAGE_HASH_PERSON_752"


def compose(*args, stdin=None, timeout=60):
    return subprocess.run(
        ["docker", "compose", "-f", "compose.yaml", *args],
        cwd=ROOT,
        input=stdin,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout,
        check=True,
    )


def psql(sql):
    return compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-U",
        "doc_admin",
        "-d",
        "doc_capture",
        "-v",
        "ON_ERROR_STOP=1",
        "-At",
        stdin=sql,
    ).stdout.strip()


def request(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        CONNECT + path,
        method=method,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=5) as response:
        data = response.read()
        return json.loads(data) if data else None


def wait_for(label, fn, seconds=120):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = fn()
            if last:
                return last
        except (OSError, subprocess.CalledProcessError, urllib.error.URLError) as exc:
            last = str(exc)
        time.sleep(2)
    raise RuntimeError(f"{label} did not complete within {seconds}s: {last}")


def records(topic):
    proc = subprocess.run(
        [
            "docker",
            "compose",
            "-f",
            "compose.yaml",
            "exec",
            "-T",
            "kafka",
            "/opt/kafka/bin/kafka-console-consumer.sh",
            "--bootstrap-server",
            "kafka:9092",
            "--topic",
            topic,
            "--from-beginning",
            "--timeout-ms",
            "2500",
            "--property",
            "print.key=true",
            "--property",
            "key.separator=|",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=20,
        check=False,
    )
    if proc.returncode:
        # The Kafka console exits with a TimeoutException after draining a topic.
        # Authentication, unknown-topic, Docker and other failures must not be
        # disguised as an empty successful read.
        classes = re.findall(
            r"\b(?:[a-z]\w*\.)+[A-Z]\w*(?:Exception|Error)\b", proc.stderr
        )
        if (
            not classes
            or any(
                name != "org.apache.kafka.common.errors.TimeoutException"
                for name in classes
            )
            or re.search(
                r"UNKNOWN_TOPIC|LEADER_NOT_AVAILABLE|does not host this topic|"
                r"authorization|authentication",
                proc.stderr,
                flags=re.IGNORECASE,
            )
        ):
            raise RuntimeError("Kafka console consumer failed: " + proc.stderr.strip())
    rows = []
    for line in proc.stdout.splitlines():
        if "|" not in line:
            continue
        raw_key, raw_value = line.split("|", 1)
        # Includes Kafka key, schema and payload, not just the payload's values.
        for forbidden in (
            SECRET,
            "content_text",
            "storage_key",
            "content_hash",
            "created_by",
            "title",
        ):
            if forbidden in line:
                raise AssertionError(
                    "private field leaked into Kafka key, schema or payload: "
                    + forbidden
                )
        key, value = json.loads(raw_key), json.loads(raw_value)
        rows.append(
            (
                key.get("payload", key),
                value.get("payload", value) if isinstance(value, dict) else value,
            )
        )
    return rows


def main():
    suffix = uuid.uuid4().hex[:10]
    connector, slot, prefix = "doc-" + suffix, "doc_" + suffix, "doc_" + suffix
    number = int(suffix[:8], 16) + 1000
    seed_doc, seed_version, new_doc, new_version, rollback_doc, sentinel_doc = (
        number * 10 + i for i in range(1, 7)
    )
    registered = False
    compose("config", "--quiet")
    compose("up", "-d", timeout=420)
    wait_for("PostgreSQL", lambda: psql("SELECT 1") == "1")
    wait_for("Connect API", lambda: request("/").get("version"))

    # The first committed row predates connector creation, so it must arrive as r.
    psql(f"""BEGIN;
      INSERT INTO cdc_demo.document VALUES ({seed_doc},10,'{SECRET}',999);
      INSERT INTO cdc_demo.document_version(id,document_id,version_no,status,content_text,storage_key,content_hash,created_by)
       VALUES ({seed_version},{seed_doc},1,'DRAFT','{SECRET}','{SECRET}','{SECRET}',999);
      COMMIT;""")
    config = {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "database.hostname": "postgres",
        "database.port": "5432",
        "database.dbname": "doc_capture",
        "database.user": "doc_capture_reader",
        "database.password": "doc_reader_local_only",
        "plugin.name": "pgoutput",
        "slot.name": slot,
        "publication.name": "doc_capture_publication",
        "publication.autocreate.mode": "disabled",
        "topic.prefix": prefix,
        "table.include.list": "cdc_demo.document,cdc_demo.document_version",
        "column.include.list": "cdc_demo.document.id,cdc_demo.document.project_id,"
        "cdc_demo.document_version.id,cdc_demo.document_version.document_id,"
        "cdc_demo.document_version.version_no,cdc_demo.document_version.status,"
        "cdc_demo.document_version.parse_status,cdc_demo.document_version.lock_version,"
        "cdc_demo.document_version.updated_at",
        "snapshot.mode": "initial",
        "key.converter": "org.apache.kafka.connect.json.JsonConverter",
        "key.converter.schemas.enable": "true",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "true",
    }
    try:
        wait_for(
            "connector registration",
            lambda: request("/connectors/" + connector + "/config", "PUT", config),
        )
        registered = True

        def running():
            status = request("/connectors/" + connector + "/status")
            if status["connector"]["state"] == "FAILED" or any(
                x["state"] == "FAILED" for x in status.get("tasks", [])
            ):
                raise RuntimeError("Connector task failed: " + json.dumps(status))
            return status.get("tasks") and all(
                x["state"] == "RUNNING" for x in status["tasks"]
            )

        wait_for("connector task", running)
        version_topic = prefix + ".cdc_demo.document_version"
        document_topic = prefix + ".cdc_demo.document"

        def has_event(topic, row_id, op):
            return [
                v
                for _, v in records(topic)
                if v
                and v.get("op") == op
                and (v.get("after") or {}).get("id") == row_id
            ]

        wait_for("document snapshot", lambda: has_event(document_topic, seed_doc, "r"))
        wait_for(
            "version snapshot", lambda: has_event(version_topic, seed_version, "r")
        )

        psql(f"""BEGIN;
          INSERT INTO cdc_demo.document VALUES ({new_doc},11,'{SECRET}',999);
          INSERT INTO cdc_demo.document_version(id,document_id,version_no,status,content_text,storage_key,content_hash,created_by)
            VALUES ({new_version},{new_doc},1,'DRAFT','{SECRET}','{SECRET}','{SECRET}',999);
          COMMIT;
          BEGIN;
          UPDATE cdc_demo.document_version SET content_text='{SECRET}_UPDATED', lock_version=lock_version+1,
            updated_at=clock_timestamp() WHERE id={seed_version};
          COMMIT;
          UPDATE cdc_demo.document_version SET status='APPROVED',lock_version=lock_version+1,
            updated_at=clock_timestamp() WHERE id={seed_version};
          UPDATE cdc_demo.document_version SET parse_status='OK',updated_at=clock_timestamp() WHERE id={seed_version};
          BEGIN;
          INSERT INTO cdc_demo.document VALUES ({rollback_doc},10,'{SECRET}',999);
          ROLLBACK;
          INSERT INTO cdc_demo.document VALUES ({sentinel_doc},10,'{SECRET}',999);""")
        wait_for("new version", lambda: has_event(version_topic, new_version, "c"))
        wait_for("later sentinel", lambda: has_event(document_topic, sentinel_doc, "c"))
        wait_for(
            "updates", lambda: len(has_event(version_topic, seed_version, "u")) >= 3
        )
        updates = has_event(version_topic, seed_version, "u")
        states = [
            (
                e["after"]["status"],
                e["after"]["parse_status"],
                e["after"]["lock_version"],
            )
            for e in updates
        ]
        assert (
            ("DRAFT", "PENDING", 1) in states
            and ("APPROVED", "PENDING", 2) in states
            and ("APPROVED", "OK", 2) in states
        )
        assert not has_event(document_topic, rollback_doc, "c"), (
            "rolled-back document was emitted"
        )

        # Stop keeps this connector name, Kafka offsets and replication slot.
        # Commit a write while Connect is down, then verify WAL catch-up on start.
        wait_for(
            "active replication slot",
            lambda: (
                psql(
                    f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}' AND active"
                )
                == "1"
            ),
        )
        before = len(has_event(version_topic, new_version, "c"))
        compose("stop", "connect", timeout=90)
        wait_for(
            "inactive replication slot while Connect is stopped",
            lambda: (
                psql(
                    f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}' AND NOT active"
                )
                == "1"
            ),
        )
        psql(
            f"UPDATE cdc_demo.document_version SET status='REJECTED',lock_version=lock_version+1 WHERE id={new_version};"
        )
        compose("start", "connect", timeout=90)
        wait_for("Connect API after restart", lambda: request("/").get("version"))
        wait_for("connector after restart", running)
        wait_for(
            "same replication slot",
            lambda: (
                psql(
                    f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}' AND active"
                )
                == "1"
            ),
        )
        wait_for(
            "update committed during Connect outage",
            lambda: any(
                event["after"].get("status") == "REJECTED"
                for event in has_event(version_topic, new_version, "u")
            ),
        )
        after = len(has_event(version_topic, new_version, "c"))
        psql(f"DELETE FROM cdc_demo.document_version WHERE id={new_version};")

        def delete_events():
            return [
                (key, event)
                for key, event in records(version_topic)
                if event
                and event.get("op") == "d"
                and (event.get("before") or {}).get("id") == new_version
            ]

        deleted = wait_for("delete event", delete_events)
        key, deleted_event = deleted[-1]
        assert key.get("id") == new_version
        # REPLICA IDENTITY DEFAULT contains the PK but not the document foreign key.
        # Connector may substitute 0 for a non-nullable missing column; it is
        # not the deleted row's true document_id and must not be trusted.
        observed_doc = deleted_event["before"].get("document_id")
        assert observed_doc != new_doc, (
            "Delete unexpectedly exposed document_id; re-evaluate replica identity"
        )
        print("PASS: pre-existing documents and versions emitted as snapshot records")
        print(
            "PASS: committed insert and content/status/parse metadata updates observed; rollback absent before sentinel"
        )
        print(
            "PASS: same connector and slot resumed after Connect outage; update committed during outage observed"
        )
        print(
            f"PASS: DELETE has version ID in key/before; before.document_id={observed_doc!r} is not the real document ID"
        )
        print(
            f"OBSERVED: new-version create records before/after restart: {before}/{after}; duplicate delivery remains possible"
        )
        print(
            "PASS: private test values and excluded column names absent from Kafka keys, schemas and values"
        )
        print(
            "LIMIT: writes during snapshot, multi-partition ordering, actual service/consumer and production security were not tested"
        )
    finally:
        if registered:
            try:
                request("/connectors/" + connector, "DELETE")
                wait_for(
                    "replication slot inactive",
                    lambda: (
                        psql(
                            f"SELECT count(*) FROM pg_replication_slots WHERE slot_name='{slot}' AND NOT active"
                        )
                        == "1"
                    ),
                    30,
                )
                psql(f"SELECT pg_drop_replication_slot('{slot}')")
            except (
                OSError,
                RuntimeError,
                subprocess.SubprocessError,
                ValueError,
                KeyError,
            ) as exc:
                print(
                    "WARNING: test connector or test slot cleanup requires inspection:",
                    exc,
                    file=sys.stderr,
                )


if __name__ == "__main__":
    try:
        main()
    except (
        OSError,
        RuntimeError,
        AssertionError,
        subprocess.SubprocessError,
        ValueError,
        KeyError,
    ) as exc:
        print("Document CDC test failed:", exc, file=sys.stderr)
        sys.exit(1)
