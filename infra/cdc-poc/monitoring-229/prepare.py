"""Prepare a disposable local stack using the production CDC/alert configuration.

The webhook file is supplied externally and is never copied into this directory.
No live development containers, databases, topics or rules are changed.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUNTIME = HERE / ".runtime"
PROJECT = "beez-cdc-monitoring-229"


def main():
    existing = subprocess.run(["docker", "ps", "--all", "--filter", "label=com.docker.compose.project=" + PROJECT, "--quiet"],
                              check=True, capture_output=True, text=True)
    if existing.stdout.strip():
        raise RuntimeError("This isolated project already exists; refusing to overwrite its active configuration")
    secret = Path(os.environ["BEEZ_229_MATTERMOST_WEBHOOK_FILE"]).resolve()
    if not secret.is_file():
        raise RuntimeError("External Mattermost webhook file missing")
    jar = ROOT / "backend/build/libs/beez-backend-0.0.1-SNAPSHOT.jar"
    agent = RUNTIME / "jmx-agent.jar"
    if hashlib.sha256(agent.read_bytes()).hexdigest() != "a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e":
        raise RuntimeError("JMX agent checksum mismatch")
    RUNTIME.mkdir(exist_ok=True)
    if (RUNTIME / "events.jsonl").exists():
        archive = RUNTIME / ("previous-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        archive.mkdir()
        for name in ("events.jsonl", "provenance.json"):
            if (RUNTIME / name).exists():
                shutil.move(str(RUNTIME / name), str(archive / name))
    shutil.copy2(jar, RUNTIME / "backend.jar")
    prom = (ROOT / "infra/monitoring/prometheus/prometheus.yml").read_text(encoding="utf-8")
    # External label marks messages as a local drill without changing alert expressions/timing.
    prom = prom.replace("global:\n", "global:\n  external_labels:\n    environment: qa229-isolated\n")
    # Host disk monitoring is outside this test and needs no host filesystem mount.
    start = prom.index("  - job_name: node\n")
    end = prom.index("  - job_name: postgres\n", start)
    prom = prom[:start] + prom[end:]
    (RUNTIME / "prometheus.yml").write_text(prom, encoding="utf-8")
    am = (ROOT / "infra/monitoring/alertmanager/alertmanager.yml").read_text(encoding="utf-8")
    am = am.replace("username: BEEZ 모니터링", "username: BEEZ 229 격리 검증")
    am = am.replace("title: '{{ template", "title: '[229 격리 검증] {{ template")
    am = am.replace("fallback: '{{ template", "fallback: '[229 격리 검증] {{ template")
    (RUNTIME / "alertmanager.yml").write_text(am, encoding="utf-8")
    def bind(source, target):
        return {"type": "bind", "source": str(source.resolve()), "target": target,
                "read_only": True, "bind": {"create_host_path": False}}
    services = {
      "postgres": {
        "image": "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d",
        "environment": {"POSTGRES_USER": "beez", "POSTGRES_PASSWORD": "qa229-only", "POSTGRES_DB": "beez"},
        "command": ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=10", "-c", "max_wal_senders=10"],
        "ports": ["127.0.0.1:15229:5432"], "tmpfs": ["/var/lib/postgresql/data:rw,size=512m"], "mem_limit": "768m"},
      "kafka": {
        "image": "apache/kafka:3.9.1@sha256:4ceccc577f03f51f6af8dbfda55194d0d892f4fa7913ffbded567ce3895622ed",
        "environment": {"KAFKA_NODE_ID":"1", "KAFKA_PROCESS_ROLES":"broker,controller",
            "CLUSTER_ID":"lGbBAJOLSYK-3s5CK-VD3g", "KAFKA_LISTENERS":"PLAINTEXT://:9092,CONTROLLER://127.0.0.1:9093",
            "KAFKA_ADVERTISED_LISTENERS":"PLAINTEXT://kafka:9092", "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP":"PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
            "KAFKA_CONTROLLER_LISTENER_NAMES":"CONTROLLER", "KAFKA_CONTROLLER_QUORUM_VOTERS":"1@127.0.0.1:9093",
            "KAFKA_INTER_BROKER_LISTENER_NAME":"PLAINTEXT", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR":"1",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR":"1", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR":"1",
            "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS":"0", "KAFKA_AUTO_CREATE_TOPICS_ENABLE":"false",
            "KAFKA_NUM_PARTITIONS":"1", "KAFKA_LOG_DIRS":"/var/lib/kafka/data", "KAFKA_HEAP_OPTS":"-Xms256m -Xmx384m",
            "KAFKA_JMX_OPTS":"-Djava.awt.headless=true"},
        "tmpfs":["/etc/kafka/secrets:size=1m", "/mnt/shared/config:size=1m"], "mem_limit":"1g"},
      "connect": {
        "image":"debezium/connect:2.7.3.Final@sha256:997b67feff614549acd20d54bfed398fc73b339881c649d42821c1e55ec9972b",
        "environment":{"BOOTSTRAP_SERVERS":"kafka:9092", "GROUP_ID":"beez-cdc-connect", "CONFIG_STORAGE_TOPIC":"beez-cdc-connect-configs",
            "OFFSET_STORAGE_TOPIC":"beez-cdc-connect-offsets", "STATUS_STORAGE_TOPIC":"beez-cdc-connect-status",
            "CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR":"1", "CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR":"1", "CONNECT_STATUS_STORAGE_REPLICATION_FACTOR":"1",
            "CONNECT_LISTENERS":"http://0.0.0.0:8083", "ADVERTISED_HOST_NAME":"connect",
            "CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE":"false", "CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE":"false",
            "HEAP_OPTS":"-Xms256m -Xmx384m", "KAFKA_JMX_OPTS":"-Djava.awt.headless=true",
            "KAFKA_OPTS":"-javaagent:/opt/jmx-agent.jar=9404:/opt/connect-metrics.yaml"},
        "volumes":[bind(agent,"/opt/jmx-agent.jar"),bind(ROOT / "infra/cdc/dev/connect-metrics.yaml","/opt/connect-metrics.yaml")],
        "ports":["127.0.0.1:18230:8083"], "tmpfs":["/kafka/config:size=16m","/kafka/data:size=16m","/kafka/logs:size=64m"], "mem_limit":"768m"},
      "backend": {
        "image":"eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a", "command":["java","-Xmx512m","-jar","/app/backend.jar"],
        "volumes":[bind(RUNTIME / "backend.jar","/app/backend.jar")], "ports":["127.0.0.1:18229:8080"], "mem_limit":"768m",
        "environment":{"SPRING_PROFILES_ACTIVE":"qa229", "SPRING_DOCKER_COMPOSE_ENABLED":"false",
            "DB_URL":"jdbc:postgresql://postgres:5432/beez", "DB_USERNAME":"beez", "DB_PASSWORD":"qa229-only",
            "AI_BASE_URL":"http://ai:8000", "AI_INTERNAL_TOKEN":"isolated-qa229-no-provider",
            "DOCUMENT_WORKER_ENABLED":"false", "VALIDATION_WORKER_ENABLED":"false",
            "DOCUMENT_S3_ENDPOINT":"http://127.0.0.1:1", "DOCUMENT_S3_BUCKET":"qa229", "DOCUMENT_S3_ACCESS_KEY":"qa229", "DOCUMENT_S3_SECRET_KEY":"qa229-only",
            "BEEZ_CDC_INTAKE_ENABLED":"true", "BEEZ_CDC_INTAKE_CONSUMER_ENABLED":"true",
            "BEEZ_CDC_INTAKE_BOOTSTRAP_SERVERS":"kafka:9092", "BEEZ_CDC_INTAKE_GROUP_ID":"beez-published-analysis",
            "BEEZ_CDC_INTAKE_TOPIC":"beez.public.published_change_analysis", "BEEZ_CDC_INTAKE_RECOVERY_POLL_MILLIS":"2000"}},
      "ai": {
        "image":"python:3.13-alpine@sha256:7984cf56ceaddebe4fefeb75d64000b9f45d8b77b11a09b24524da6cdc3af0c8",
        "command":["python","/app/ai_fixture.py"], "volumes":[bind(HERE / "ai_fixture.py", "/app/ai_fixture.py")], "mem_limit":"64m"},
      "prometheus": {
        "image":"prom/prometheus:v3.15.1@sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2",
        "command":["--config.file=/etc/prometheus/prometheus.yml","--storage.tsdb.path=/prometheus"],
        "volumes":[bind(RUNTIME / "prometheus.yml","/etc/prometheus/prometheus.yml"),bind(ROOT / "infra/monitoring/prometheus/rules","/etc/prometheus/rules")],
        "ports":["127.0.0.1:19229:9090"], "mem_limit":"512m"},
      "alertmanager": {
        "image":"prom/alertmanager:v0.34.2@sha256:39d8bb3966c694a124428a3a88fbcffd40bd069129ecd47c059f8a3f17c55f3b",
        "command":["--config.file=/etc/alertmanager/alertmanager.yml","--storage.path=/alertmanager","--cluster.listen-address="],
        "volumes":[bind(RUNTIME / "alertmanager.yml","/etc/alertmanager/alertmanager.yml"), bind(ROOT / "infra/monitoring/alertmanager/templates","/etc/alertmanager/templates"),bind(secret,"/run/secrets/mattermost_webhook_url")],
        "ports":["127.0.0.1:19230:9093"], "mem_limit":"128m"},
      "postgres-exporter": {
        "image":"prometheuscommunity/postgres-exporter:v0.20.1@sha256:ac5ec343104fae0e2d84a27bb8d69b38430a11910c5382cad85d478d2bab713e",
        "environment":{"DATA_SOURCE_URI":"postgres:5432/beez?sslmode=disable", "DATA_SOURCE_USER":"beez", "DATA_SOURCE_PASS":"qa229-only"},"mem_limit":"128m"},
      "kafka-exporter": {
        "image":"danielqsj/kafka-exporter:v1.10.0@sha256:346a53c2489a3530790d01f7b96632496d0d58926662f4c35bb65f569db2ab42",
        "command":["--kafka.server=kafka:9092","--kafka.version=3.9.0","--topic.filter=^beez\\.public\\.published_change_analysis$","--group.filter=^beez-published-analysis$"],"mem_limit":"128m", "restart":"on-failure"}
    }
    # No named volumes: restarts preserve container writable layers; final down removes only this project.
    (RUNTIME / "compose.json").write_text(json.dumps({"name":PROJECT,"services":services},indent=2),encoding="utf-8")
    (RUNTIME / "provenance.json").write_text(json.dumps({"jar_sha256":hashlib.sha256(jar.read_bytes()).hexdigest(),
        "rules_sha256":hashlib.sha256((ROOT / "infra/monitoring/prometheus/rules/beez.yml").read_bytes()).hexdigest(),
        "production_thresholds_unchanged":True},indent=2),encoding="utf-8")
    print("Prepared isolated monitoring stack; webhook contents were not logged")

if __name__ == "__main__":
    main()
