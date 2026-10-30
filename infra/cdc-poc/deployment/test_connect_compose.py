"""Test deployment wiring with an isolated, disposable, plaintext broker.

Never points at existing application infrastructure. Requires cached local Docker
images and a running Docker engine. No ports, volumes or external network access.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEPLOYMENT = ROOT / "cdc" / "compose.yaml"
OVERRIDE = HERE / "test_override.yaml"
project = "beez-cdc-deploytest-" + uuid.uuid4().hex[:10]
network = project + "-network"


def run(*args, env=None, check=True):
    return subprocess.run(args, env=env, text=True, capture_output=True, check=check)


def main():
    # Required deployment values must not silently select defaults.
    clean = os.environ.copy()
    for key in ("CDC_CONNECT_IMAGE", "CDC_CONNECT_SECRETS_DIR", "CDC_PRIVATE_NETWORK"):
        clean.pop(key, None)
    absent = run("docker", "compose", "-f", str(DEPLOYMENT), "config", "--quiet", env=clean, check=False)
    assert absent.returncode != 0, "missing deployment values should be rejected"

    with tempfile.TemporaryDirectory(prefix="beez-cdc-config-test-") as tmp:
        Path(tmp, "connect-worker.properties").write_text(
            "bootstrap.servers=broker:9092\n"
            "group.id=isolated-test-connect\n"
            "config.storage.topic=isolated-config\n"
            "offset.storage.topic=isolated-offset\n"
            "status.storage.topic=isolated-status\n"
            "config.storage.replication.factor=1\n"
            "offset.storage.replication.factor=1\n"
            "status.storage.replication.factor=1\n"
            "key.converter=org.apache.kafka.connect.json.JsonConverter\n"
            "key.converter.schemas.enable=false\n"
            "value.converter=org.apache.kafka.connect.json.JsonConverter\n"
            "value.converter.schemas.enable=false\n"
            "plugin.path=/kafka/connect\n"
            "rest.advertised.host.name=cdc-connect\n",
            encoding="utf-8",
        )
        env = clean | {
            "CDC_CONNECT_IMAGE": "debezium/connect:2.7.3.Final",
            "CDC_CONNECT_SECRETS_DIR": tmp,
            "CDC_PRIVATE_NETWORK": network,
        }
        compose = ["docker", "compose", "-p", project, "-f", str(DEPLOYMENT), "-f", str(OVERRIDE), "--profile", "cdc"]
        cfg = json.loads(run(*compose, "config", "--format", "json", env=env).stdout)
        assert not cfg["services"]["cdc-connect"].get("ports"), "REST must not publish a host port"
        assert not cfg["services"]["broker"].get("ports"), "test broker must not publish a host port"
        created = False
        try:
            run("docker", "network", "create", "--internal", network)
            created = True
            run(*compose, "up", "-d", env=env)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                response = run(*compose, "exec", "-T", "cdc-connect", "curl", "-fsS", "http://localhost:8083/connector-plugins", env=env, check=False)
                if response.returncode == 0:
                    plugins = json.loads(response.stdout)
                    assert any(item.get("class") == "io.debezium.connector.postgresql.PostgresConnector" for item in plugins)
                    print("PASS: required settings, isolated broker, worker startup and PostgreSQL plugin")
                    break
                time.sleep(3)
            else:
                logs = run(*compose, "logs", "--tail=30", env=env, check=False)
                raise RuntimeError("isolated worker did not become ready:\n" + logs.stdout + logs.stderr)
        finally:
            if created:
                run(*compose, "down", "--remove-orphans", env=env, check=False)
                run("docker", "network", "rm", network, check=False)


if __name__ == "__main__":
    main()
