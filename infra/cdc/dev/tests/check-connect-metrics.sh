#!/usr/bin/env bash
# connect-metrics.yaml 시험 (S15P31A108-261). CI cdc-stack 잡과 로컬에서 같은 것을 돌린다.
#
#   cd infra/cdc/dev && bash tests/check-connect-metrics.sh <jmx_prometheus_javaagent jar 경로>
#
# 서버 Connect 와 같은 javaagent 를 빈 JVM(tests/ConnectMetricsCheck.java, Connect 와 같은 이름의 MBean)에 붙여
#  1) 에이전트가 설정을 읽고 뜨는지 — 틀리면 JVM 이 exit 1 로 끝나 서버에서는 Connect 가 뜨지 않는다
#  2) 알림(rules/beez.yml)·대시보드가 읽는 시계열이 그 이름·라벨로 나오는지
# 를 본다. java 11 이상(단일 파일 실행)과 curl 이 필요하다.
set -euo pipefail

jar=${1:?jar 경로를 준다}
port=${PORT:-19404}

java -javaagent:"$jar"=127.0.0.1:"$port":connect-metrics.yaml tests/ConnectMetricsCheck.java 60000 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT

# 에이전트 HTTP 는 main 보다 먼저 뜬다(premain). 시험 MBean 은 main 이 단일 파일을 컴파일한 뒤 올리므로, 마지막에 올리는
# Debezium MBean 이 보일 때까지 기다린다. 그 이름이 설정에서 빠져도 30초 뒤 받은 것으로 아래에서 판정한다
out=""
for _ in $(seq 1 30); do
  if out=$(curl -fsS "http://127.0.0.1:$port/metrics" 2>/dev/null) && grep -q '^debezium_' <<<"$out"; then break; fi
  if ! kill -0 "$pid" 2>/dev/null; then
    wait "$pid" || true
    echo "::error::javaagent 가 JVM 을 끝냈다 — connect-metrics.yaml 을 읽지 못함(서버에서는 Connect 가 뜨지 않는다)"
    exit 1
  fi
  sleep 1
done
if [ -z "$out" ]; then echo "::error::30초 안에 지표 응답이 없다"; exit 1; fi

fail=0
while IFS= read -r want; do
  if ! grep -qxF "$want" <<<"$out"; then echo "::error::지표 없음: $want"; fail=1; fi
done <<'EOF'
kafka_connect_connector_status{connector="beez-published-analysis-1",status="running"} 1.0
kafka_connect_task_status{connector="beez-published-analysis-1",status="running",task="0"} 1.0
kafka_connect_worker_connector_count 1.0
debezium_streaming_connected{server="beez"} 1.0
EOF
if [ "$fail" -ne 0 ]; then
  echo "--- 받은 지표(kafka_connect·debezium) ---"
  grep -E '^(kafka_connect|debezium)' <<<"$out" || true
  exit 1
fi
echo "ok: javaagent 기동, 알림이 읽는 시계열 4개"
