"""Run #227 against an isolated Kafka broker and a Testcontainers PostgreSQL."""
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
COMPOSE_FILE = Path(__file__).resolve().parent / "compose.yaml"
COMPOSE = ["docker", "compose", "-p", "beez-cdc-intake-227-test", "-f", str(COMPOSE_FILE)]


def main():
    existing = subprocess.run(COMPOSE + ["ps", "--all", "--quiet"], capture_output=True, text=True, check=True)
    if existing.stdout.strip():
        raise RuntimeError("The #227 test Compose project already exists; refusing to alter it")
    suffix = uuid.uuid4().hex[:12]
    topic, group = "beez227_" + suffix, "beez227_" + suffix
    started = False
    try:
        started = True
        subprocess.run(COMPOSE + ["up", "-d"], check=True, timeout=180)
        deadline = time.monotonic() + 120
        while True:
            result = subprocess.run(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
                                    "--bootstrap-server", "kafka:9092", "--list"], capture_output=True)
            if result.returncode == 0:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Kafka did not become ready")
            time.sleep(1)
        subprocess.run(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
                        "kafka:9092", "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1"],
                       check=True, timeout=60)
        env = os.environ.copy()
        env.update({"BEEZ_227_KAFKA_BOOTSTRAP": "127.0.0.1:29127", "BEEZ_227_KAFKA_TOPIC": topic,
                    "BEEZ_227_KAFKA_GROUP": group})
        return subprocess.run(["cmd.exe", "/c", "gradlew.bat", "--offline", "integrationTest", "--tests",
                               "*PublishedAnalysisKafkaIntegrationTest"], cwd=ROOT / "backend", env=env,
                              timeout=300).returncode
    finally:
        if started:
            # The project name is dedicated to this test; no named volumes are removed.
            subprocess.run(COMPOSE + ["down"], check=True, timeout=90)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("#227 Kafka integration failed:", error, file=sys.stderr)
        sys.exit(1)
