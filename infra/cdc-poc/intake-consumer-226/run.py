"""Run a Spring Kafka intake consumer test against one isolated Kafka broker."""
import os
import pathlib
import subprocess
import sys
import time
import uuid

HERE = pathlib.Path(__file__).resolve().parent
BACKEND = HERE.parents[2] / "backend"
COMPOSE = ["docker", "compose", "-f", str(HERE / "compose.yaml")]


def main():
    existing = subprocess.run(COMPOSE + ["ps", "--all", "--quiet"], capture_output=True, text=True, check=True)
    if existing.stdout.strip():
        raise RuntimeError("Intake consumer test project already exists; refusing to alter it")
    topic = "intake_226_" + uuid.uuid4().hex[:12]
    group = "intake_226_" + uuid.uuid4().hex[:12]
    started = False
    try:
        started = True
        subprocess.run(COMPOSE + ["up", "-d"], check=True, timeout=180)
        deadline = time.monotonic() + 120
        while True:
            result = subprocess.run(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
                "--bootstrap-server", "kafka:9092", "--list"], capture_output=True, text=True)
            if result.returncode == 0:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Kafka did not start")
            time.sleep(1)
        subprocess.run(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
            "--bootstrap-server", "kafka:9092", "--create", "--topic", topic,
            "--partitions", "1", "--replication-factor", "1"], check=True, timeout=60)
        env = os.environ.copy()
        env.update({"INTAKE_226_TEST_SOURCE": str(HERE / "java"), "INTAKE_226_TEST_TOPIC": topic,
                    "INTAKE_226_TEST_GROUP": group, "INTAKE_226_TEST_BOOTSTRAP": "127.0.0.1:29126"})
        return subprocess.run(["cmd.exe", "/c", "gradlew.bat", "integrationTest", "--tests",
            "*IntakeConsumerLiveIntegrationTest", "-I", str(HERE / "e2e-init.gradle")],
            cwd=BACKEND, env=env, timeout=300).returncode
    finally:
        if started:
            # Only our newly created Compose project; volumes are not removed.
            subprocess.run(COMPOSE + ["down"], timeout=90, check=True)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Intake consumer E2E failed:", error, file=sys.stderr)
        sys.exit(1)
