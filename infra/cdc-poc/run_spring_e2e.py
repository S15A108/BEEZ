"""Opt-in isolated Kafka listener E2E. Never connects to an application DB."""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

import smoke_test

ROOT = pathlib.Path(__file__).resolve().parent
BACKEND = ROOT.parent.parent / "backend"


def main():
    suffix = uuid.uuid4().hex[:12]
    connector = "beez-cdc-spring-e2e-" + suffix
    topic_prefix = "beez_spring_e2e_" + suffix
    slot = "beez_spring_" + suffix
    topic = topic_prefix + ".cdc_demo.publication_outbox"
    existing = smoke_test.docker("ps", "--status", "running", "--services").stdout.splitlines()
    registered = False
    try:
        result = smoke_test.docker("up", "-d", timeout=180)
        if result.returncode:
            raise RuntimeError("Compose startup failed: " + result.stderr[-1500:])
        deadline = time.monotonic() + 120
        while True:
            try:
                smoke_test.request("/")
                break
            except (OSError, urllib.error.URLError):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Connect did not start")
                time.sleep(2)
        config = json.loads((ROOT / "connector.json").read_text(encoding="utf-8-sig"))["config"]
        config.update({"topic.prefix": topic_prefix, "slot.name": slot, "publication.name": slot,
                       "slot.drop.on.stop": "true"})
        deadline = time.monotonic() + 120
        while True:
            try:
                if not registered:
                    smoke_test.request("/connectors/" + connector + "/config", "PUT", config)
                    registered = True
                status = smoke_test.request("/connectors/" + connector + "/status")
                tasks = status.get("tasks", [])
                if status["connector"]["state"] == "FAILED" or any(t["state"] == "FAILED" for t in tasks):
                    raise RuntimeError("E2E connector failed: " + json.dumps(status))
                active = smoke_test.psql("SELECT count(*) FROM pg_replication_slots WHERE slot_name='" + slot + "' AND active;").strip()
                if tasks and all(t["state"] == "RUNNING" for t in tasks) and active == "1":
                    break
            except urllib.error.HTTPError as error:
                if error.code not in (404, 409, 503):
                    raise
            if time.monotonic() >= deadline:
                raise RuntimeError("E2E connector not ready")
            time.sleep(2)
        env = os.environ.copy()
        env.update({"BEEZ_CDC_E2E_DIR": str(ROOT), "BEEZ_CDC_E2E_TEST_DIR": str(ROOT / "java"),
                    "BEEZ_CDC_E2E_GROUP": "beez-spring-e2e-" + suffix, "BEEZ_CDC_E2E_TOPIC": topic})
        command = ["cmd.exe", "/c", "gradlew.bat", "integrationTest", "--tests", "*PublicationKafkaLiveIntegrationTest", "-I", str(ROOT / "e2e-init.gradle")]
        return subprocess.run(command, cwd=BACKEND, env=env, timeout=300).returncode
    finally:
        if registered:
            try:
                req = urllib.request.Request(smoke_test.CONNECT_URL + "/connectors/" + connector, method="DELETE")
                with urllib.request.urlopen(req, timeout=10):
                    pass
            except Exception as error:
                print("E2E connector cleanup failed; inspect", connector, ":", error, file=sys.stderr)
        if not existing:
            # Only the named beez-cdc-poc Compose project, never volumes.
            smoke_test.docker("down", timeout=90)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print("Spring CDC E2E failed:", exc, file=sys.stderr)
        sys.exit(1)
