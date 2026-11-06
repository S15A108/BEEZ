# dev CDC 스택(Kafka·Debezium Connect) 런북 (S15P31A108-294)

dev 서버에 Kafka 단일 broker와 Debezium Connect를 compose 프로젝트 `beez-cdc`로 띄우는 절차다.
결정과 이유는 [CDC 배포 계획](cdc-deploy-plan-279.md) D1~D3이 정본이고, 이 문서는 그 실행 방법만 적는다.

- PostgreSQL 쪽 준비(`wal_level`·복제 계정·publication·비밀 파일)는 S15P31A108-293 런북 [cdc-postgres.md](cdc-postgres.md).
- 커넥터 등록과 backend consumer 켜기는 S15P31A108-295([cdc-connector.md](cdc-connector.md)). **이 스택만 올린 상태에서는 아무것도 흐르지 않는다.**

## 구성

| 항목 | 값 |
|---|---|
| compose 파일 | `infra/cdc/dev/compose.yaml` (서버 `/opt/beez/cdc/compose.yaml`). 옆의 `infra/cdc/compose.yaml`은 운영 검토안이라 관계없다 |
| 프로젝트 이름 | `beez-cdc`. 본 스택 `beez`와 달라 CD의 `--remove-orphans`가 지우지 않는다 |
| 배포 | 손으로(아래 "올리기·업데이트"). `infra/cdc/**`만 바뀐 dev 머지는 CD가 돌지 않는다(`cd.yml` `paths-ignore`) |
| kafka | `apache/kafka:3.9.1`(digest 고정), KRaft 단일 노드(broker+controller), 힙 512MB·상한 1GB, 데이터 볼륨 `beez-cdc_kafka-data` |
| connect | `debezium/connect:2.7.3.Final`(digest 고정, 안의 Kafka Connect 3.7.0, `uid=1001`), distributed 모드 worker 1개, 힙 512MB·상한 1GB |
| 네트워크 | 본 스택의 `beez_default`(외부)만. 서비스 이름 `kafka`·`connect` |
| 호스트 포트 | **없음** |
| 컨테이너 안에서만 여는 포트 | Connect REST `127.0.0.1:8083`, Kafka controller `127.0.0.1:9093` |
| 같은 망에 여는 포트 | Kafka broker `9092`(PLAINTEXT). `beez_default`의 모든 컨테이너가 읽고 쓸 수 있다(계획 D3 "남는 위험"으로 수용) |
| 비밀 디렉터리 | 서버 `/opt/beez/.cdc-secrets/`(스택 폴더 밖) → 컨테이너 `/run/cdc-secrets` 읽기 전용. `root:1001` `2750`(`drwxr-s---`, setgid는 `/opt/beez`에서 상속) |
| Connect 내부 토픽 | `beez-cdc-connect-configs`(1)·`-offsets`(25)·`-status`(5) 파티션, 모두 compact·복제 1. Connect가 기동할 때 만든다 |
| 접수 토픽 | `beez.public.published_change_analysis`, 파티션 1, 복제 1, `retention.ms` 7일. **손으로 만든다** |

설정에서 눈여겨볼 것(이유는 compose 파일 주석):

- broker 내부 토픽 복제 수 1(`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` 등). 기본값 3이면 consumer group이 붙지 못한다.
- 토픽 자동 생성 끔(`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`). Connect 내부 토픽은 Connect가 관리 API로 따로 만든다.
- Kafka 데이터 경로를 볼륨(`/var/lib/kafka/data`)으로 옮겼다. 이미지 기본값은 `/tmp` 아래라 다시 만들면 사라진다.
- `KAFKA_JMX_OPTS`를 채워 JVM이 JMX(RMI) 커넥터를 임의 포트에 열지 않게 했다.
- Connect의 `/kafka/config`는 tmpfs다. 진입 스크립트가 env로 설정 파일을 덧쓰는데, 익명 볼륨이면 env에서 지운 값이
  옛 파일에 남는다.
- 두 이미지가 VOLUME으로 선언한 나머지 경로(connect의 `/kafka/data`·`/kafka/logs`, kafka의 `/etc/kafka/secrets`·
  `/mnt/shared/config`)도 tmpfs로 덮었다. 그냥 두면 익명 볼륨이 생겨 `cdc down`을 할 때마다 이름 없는 볼륨이 남고,
  `docker volume ls | grep beez-cdc`에도 잡히지 않는다. 그래서 이 스택의 볼륨은 `beez-cdc_kafka-data` 하나뿐이다.
- 비밀 디렉터리는 `create_host_path: false`로 붙인다. 없으면 Docker가 root 소유 빈 디렉터리를 만드는 대신
  `bind source path does not exist`로 멈춘다.
- file config provider(`config.providers=file`)가 켜져 있다. 커넥터 설정의
  `${file:/run/cdc-secrets/database.properties:password}`를 등록할 때(295) 읽는다.

## 처음 올리기 (서버당 한 번)

본 스택이 떠 있어 `beez_default` 네트워크가 있어야 한다. 순서대로 한다.

### 1. 비밀 디렉터리

```bash
# 서버 (ssh beez)
sudo install -d -o root -g 1001 -m 750 /opt/beez/.cdc-secrets && sudo ls -ld /opt/beez/.cdc-secrets
# drwxr-s--- 2 root 1001 ... /opt/beez/.cdc-secrets
```

- 293 런북과 같은 명령이다. 이미 있으면 소유자·권한만 같은 값으로 맞추고 안의 파일은 건드리지 않는다.
- 권한의 `s`(2750)는 부모 `/opt/beez`의 setgid가 상속된 것이고 무해하다. 안에 만드는 파일의 그룹이 1001로 맞춰진다.
- 그룹 1001은 Connect 컨테이너 사용자(`kafka`)다. 서버에는 gid 1001의 그룹 이름이 없어서 `ls`에는 `1001`로,
  `stat -c %G`에는 `UNKNOWN`으로 나온다. 정상이다.
- 비밀번호 파일(`database.properties`)은 293이 쓴다. 이 스택은 파일이 없어도 뜬다(등록 때만 읽음).

### 1-2. javaagent jar (261부터)

아래 ["지표" → "jar 받기"](#jar-받기-서버당-한-번-버전을-올릴-때마다)를 먼저 한다. jar가 없으면 3단계에서 connect가
`bind source path does not exist`로 뜨지 않는다(CDC 전달이 멈추고 복구 스캔만 돈다). 서버를 새로 세우거나 `/opt/beez`를
옮길 때도 이 단계를 빼먹지 않는다.

### 2. 파일 올리기

아래 "올리기·업데이트"의 명령을 그대로 쓴다. 처음에는 기존 폴더가 없어 옮기는 단계를 건너뛴다.

### 3. 띄우기

```bash
# 서버 (ssh beez)
cdc() { docker compose -f /opt/beez/cdc/compose.yaml "$@"; }
kt() { docker exec -e KAFKA_HEAP_OPTS=-Xmx128m beez-cdc-kafka-1 /opt/kafka/bin/"$@"; }
sudo ss -Htln | awk '{print $4}' | sort > /tmp/ss-before-cdc.txt
cdc up -d --wait && cdc ps --format '{{.Service}}\t{{.Status}}\t{{.Ports}}'
# kafka    Up ... (healthy)   9092/tcp
# connect  Up ... (healthy)   8083/tcp, 8778/tcp, 9092/tcp
```

- `cdc`·`kt`는 이 셸에서만 쓰는 함수다. 새 셸에서는 앞의 두 줄을 다시 붙여 넣는다. env 파일은 없다(비밀값은 디렉터리 파일뿐).
- `kt`는 kafka 컨테이너 안의 도구(`kafka-topics.sh` 등)를 힙 128MB로 돌린다. 그냥 `docker exec`로 돌리면 도구가
  broker 힙 설정(512MB)을 물려받아 broker와 같은 1GB 상한을 나눠 쓰고, 상한에 닿으면 OOM이 broker를 죽인다.
  **kafka 컨테이너 안 도구는 항상 `kt`로 돌린다.**
- `ss` 결과(리슨 주소만, 정렬)는 아래 "확인" 2번에서 띄운 뒤와 비교한다. 큐 길이·출력 순서·pid는 다른 스택이
  다시 만들어지기만 해도 바뀌어 거짓 경보가 나므로 뺐다.
- `Ports` 칸의 `8083/tcp` 등은 이미지가 선언만 한 포트다. `0.0.0.0:`이 붙지 않았으면 호스트에 열린 것이 아니다.
- `--wait`는 둘 다 healthy가 될 때까지 기다린다. 처음에는 이미지(약 2.5GB)를 받느라 몇 분 걸린다.

### 4. 접수 토픽 만들기

```bash
# 서버 (ssh beez)
kt kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic beez.public.published_change_analysis --partitions 1 --replication-factor 1 --config retention.ms=604800000
kt kafka-topics.sh --bootstrap-server localhost:9092 --describe \
  --topic beez.public.published_change_analysis
# Topic: beez.public.published_change_analysis ... PartitionCount: 1  ReplicationFactor: 1  Configs: retention.ms=604800000
```

- 이름에 `.`과 `_`가 섞였다는 경고(metric 이름 충돌)는 무시한다. 토픽 이름은 Debezium이 `<prefix>.<schema>.<table>`로 정한다.
- 토픽은 볼륨에 남으므로 한 번만 만든다. 이미 있으면 `already exists`로 끝난다.
- heartbeat를 쓰지 않으므로(계획 D6) heartbeat 토픽은 만들지 않는다.
- **이 토픽에 시험 메시지를 쓰지 않는다.** consumer를 켜면 없는 id를 끝없이 재시도해 파티션이 막힌다(계획 D3).

## 확인

`ssh beez`로 들어가 위 `cdc`·`kt` 함수를 정의한 셸에서 친다.

```bash
# 서버 (ssh beez)
# 1) 둘 다 healthy
cdc ps --format '{{.Service}}\t{{.Status}}'

# 2) 호스트에 새 리슨 주소가 없다. 출력이 비어 있어야 한다(줄이 보이면 sudo ss -tlnp 로 그 포트의 프로세스를 본다).
#    기준 파일은 띄우기 직전에 만든 것이다. 없으면(재부팅으로 /tmp 가 비는 등) 비교하지 않고 2b 로만 본다
if [ -f /tmp/ss-before-cdc.txt ]; then sudo ss -Htln | awk '{print $4}' | sort | diff /tmp/ss-before-cdc.txt - | grep '^>'
else echo '기준 파일 없음 — 2b 만 본다'; fi
# 2b) 이 스택 컨테이너에 호스트 포트 연결이 없다. 출력이 비어 있어야 한다(->가 보이면 호스트에 publish 된 것)
docker ps -a --filter label=com.docker.compose.project=beez-cdc --format '{{.Names}} {{.Ports}}' | grep -- '->'

# 3) 같은 망의 backend·ai 에서 Connect REST·controller 가 닿지 않는다. 000 / False 면 통과
docker exec beez-backend-1 sh -c 'for u in connect:8083 kafka:9093; do printf "%s " $u; curl -s -m 5 -o /dev/null -w "%{http_code}\n" http://$u/; done'
docker exec beez-ai-1 python -c "
import socket
for h, p in (('connect', 8083), ('kafka', 9093)):
    s = socket.socket(); s.settimeout(5)
    print(h, p, s.connect_ex((h, p)) == 0); s.close()"

# 4) Connect 는 컨테이너 안에서만 응답한다
docker exec beez-cdc-connect-1 curl -s http://127.0.0.1:8083/ ; echo

# 5) worker 설정: REST 주소·file provider
docker exec beez-cdc-connect-1 grep -E '^(listeners|config\.providers)' /kafka/config/connect-distributed.properties
# listeners=http://127.0.0.1:8083
# config.providers.file.class=org.apache.kafka.common.config.provider.FileConfigProvider
# config.providers=file

# 6) 293 이 비밀번호 파일을 쓴 뒤: Connect 사용자로 읽힌다(내용은 출력하지 않는다)
docker exec beez-cdc-connect-1 test -r /run/cdc-secrets/database.properties && echo '읽기 OK'

# 7) 토픽 목록: 접수 토픽 + Connect 내부 토픽 셋 + __consumer_offsets(Connect worker 그룹이 만든다)
kt kafka-topics.sh --bootstrap-server localhost:9092 --list
```

- 1)의 healthy는 **worker 상태**다. kafka는 broker 응답, connect는 worker가 그룹에 들어가 REST가 답하는지만 본다.
  295에서 커넥터를 등록한 뒤에는 커넥터·task가 FAILED여도 healthy로 보인다. 그때부터는 커넥터 상태를 따로 본다:
  `docker exec beez-cdc-connect-1 curl -s 'http://127.0.0.1:8083/connectors?expand=status'` (connector·task 모두 RUNNING).
  261부터는 아래 "지표"로 자동 수집하고 `CdcConnectorNotRunning` 등이 알린다.
- 3)에서 `000`은 연결 거부다. 두 포트는 컨테이너 안 127.0.0.1에만 열려 있다. broker `kafka:9092`는 같은 망에서
  닿는 것이 정상이다(backend consumer가 쓴다).
- 6)이 실패하면 파일·디렉터리 소유자와 권한(`root:1001`, 디렉터리 2750·파일 640)을 본다. 600이면 Connect가 읽지 못해 295의 커넥터
  등록이 실패한다.

## 지표 (S15P31A108-261)

Connect의 커넥터·task 상태를 Prometheus로 보낸다. Connect JVM 안에 **Prometheus JMX exporter javaagent**를 붙여 MBean을
`connect:9404`의 HTTP로 내고, 모니터링 스택의 Prometheus가 job `connect`로 긁는다. Kafka lag은 이 스택이 아니라 모니터링 스택의
kafka-exporter(job `kafka`)가 broker에 Kafka 프로토콜로 붙어 센다([monitoring.md 구성](monitoring.md#구성)). 알림은
[monitoring-alerts.md "Kafka·Connect 알림 공통"](monitoring-alerts.md#kafkaconnect-알림-공통-s15p31a108-261).

| 항목 | 값 |
|---|---|
| javaagent | `jmx_prometheus_javaagent-1.6.0.jar` — [prometheus/jmx_exporter 릴리스 1.6.0](https://github.com/prometheus/jmx_exporter/releases/tag/1.6.0) (1.1.0부터 Maven Central에 없음) |
| sha256 | `a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e` (릴리스의 `.sha256`과 같음, 11-06 로컬 확인) |
| 서버 위치 | `/opt/beez/.cdc-agents/` — 스택 폴더 밖(올리기 때 폴더가 통째로 바뀜). 비밀이 아니라 `root` 소유 `644` |
| 붙이는 법 | compose의 connect `KAFKA_OPTS=-javaagent:…=9404:…/connect-metrics.yaml`. jar는 `create_host_path: false` 바인드 마운트라 **없으면 connect가 뜨지 않는다** |
| 내보내는 것 | `infra/cdc/dev/connect-metrics.yaml`의 허용 목록뿐: 커넥터·task 상태(`kafka_connect_connector_status`·`kafka_connect_task_status`, 상태는 라벨), 커넥터별 task 수, worker 커넥터·task 수, Debezium 스트리밍 연결 여부·마지막 이벤트 뒤 시간·본 이벤트 수, 에이전트 기본 JVM 지표(힙·RSS) |
| 라벨 | 커넥터 이름·task 번호·상태 문자열·Debezium `server`(`beez`)뿐. 설정값·비밀번호·접수행 값 없음 |
| 메모리 | 붙이기 전후 connect 638 → 641MiB(11-06 로컬, 커넥터 RUNNING) |
| 노출 | 호스트 포트 없음. 9404는 `beez_default` 안에서 열려 **같은 망 컨테이너가 읽을 수 있다**(읽기 전용 지표). 계획 D3 "남는 위험"에 더한 것 |

- 이미지의 `ENABLE_JMX_EXPORTER` 훅은 쓰지 않는다. jar가 이미지에 없고(debezium 2.7.3에는 jolokia만 있음), 훅이 읽는
  설정 경로 `/kafka/config`가 이 compose에서 tmpfs다.
- `KAFKA_OPTS`는 컨테이너 env라 `docker exec`로 connect 안에서 JVM 도구를 돌리면 같은 javaagent가 9404를 다시 열려다 실패한다.
  Kafka 도구는 kafka 컨테이너의 `kt`로 돌린다. `curl`(REST 조회 `crest`)은 상관없다.
- 상태 문자열은 Connect 소문자 그대로다: `running`·`paused`·`failed`·`unassigned`·`restarting`·`stopped`. pause한 커넥터도 슬롯은
  활성으로 남는다(11-06 로컬).
- **설정이 틀리면 Connect가 뜨지 않는다.** YAML·정규식 오류나 파일이 없으면 javaagent가 JVM을 `exit 1`로 끝낸다(11-06 로컬 실측).
  그래서 `connect-metrics.yaml`을 바꾸면 PR 전에 아래로 시험한다. CI `cdc-stack` 잡도 같은 것을 돌린다(jar 버전은 compose의 기본
  경로에서 읽고 sha256으로 확인). Connect와 같은 이름의 가짜 MBean을 올린 빈 JVM에 jar를 붙여, 기동하는지와 알림이 읽는 시계열
  네 개가 그 이름·라벨로 나오는지 본다.

```bash
# 로컬 PC (Git Bash, infra/cdc/dev). java 11 이상. jar 는 아래 "jar 받기"와 같은 파일(sha256 확인한 것)
bash tests/check-connect-metrics.sh <jar 경로>
# ok: javaagent 기동, 알림이 읽는 시계열 4개
```

### jar 받기 (서버당 한 번, 버전을 올릴 때마다)

이 스택을 261 이후 커밋으로 올리기 **전에** 한다. jar가 없으면 `cdc up`이 connect를 만들다 `bind source path does not exist`로 멈춘다.

```bash
# 서버 (ssh beez). 받은 파일을 sha256 으로 확인한 뒤에만 옮긴다. 확인이 실패하면 아무것도 바뀌지 않는다
V=1.6.0; SUM=a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e; J=jmx_prometheus_javaagent-$V.jar
curl -fsSL -o "/tmp/$J" "https://github.com/prometheus/jmx_exporter/releases/download/$V/$J" &&
  echo "$SUM  /tmp/$J" | sha256sum -c - &&
  sudo install -d -o root -g root -m 755 /opt/beez/.cdc-agents &&
  sudo install -o root -g root -m 644 "/tmp/$J" "/opt/beez/.cdc-agents/$J"
rm -f "/tmp/$J"; ls -l /opt/beez/.cdc-agents/
# -rw-r--r-- 1 root root 10714619 ... jmx_prometheus_javaagent-1.6.0.jar
```

- 버전을 올릴 때는 이 블록의 `V`·`SUM`, compose의 기본 경로(`CDC_JMX_AGENT_JAR`), 위 표를 함께 바꾸고, 새 jar를 먼저 받은 뒤
  스택을 올린다. 옛 jar는 되돌릴 때까지 남겨 둔다.

### 지표 확인

```bash
# 서버 (ssh beez). mon 함수는 monitoring.md "처음 올리기" 4단계. prometheus 컨테이너가 beez_default 에서 connect 를 긁는 경로 그대로 본다
mon exec -T prometheus wget -qO- http://connect:9404/metrics | grep -E '^(kafka_connect_(connector|task)_status|debezium_streaming_connected)'
# kafka_connect_connector_status{connector="beez-published-analysis-1",status="running"} 1.0
# kafka_connect_task_status{connector="beez-published-analysis-1",status="running",task="0"} 1.0
# debezium_streaming_connected{server="beez"} 1.0
```

- `connect-metrics.yaml`만 바꾼 올리기는 compose 설정이 그대로라 connect가 다시 만들어지지 않는다. 이 파일은 바인드 마운트라
  옛 폴더(`cdc.old`)의 파일을 계속 본다. 그래서 그때는 `cdc up -d --wait --force-recreate connect`로 다시 만든다.

## 올리기·업데이트

설정이나 이미지 버전을 바꿀 때는 PR로 `infra/cdc/dev/`를 고쳐 dev에 넣은 뒤 아래를 돌린다. 서버에서 파일을 직접
고치지 않는다(다음 올리기에 덮인다).

```bash
# 로컬 PC (Git Bash, 저장소 루트). REV 는 올릴 커밋. PR 검증 중이면 그 브랜치의 커밋(HEAD 등)
REV=origin/dev
git fetch -q origin && SHA=$(git rev-parse "$REV") && echo "$SHA" &&
git archive "$SHA" infra/cdc/dev | ssh beez "set -e; cd /opt/beez
  rm -rf cdc.new && mkdir cdc.new
  tar -x -C cdc.new --strip-components=3
  echo $SHA > cdc.new/REVISION
  rm -rf cdc.old; if [ -d cdc ]; then mv cdc cdc.old; fi
  mv cdc.new cdc && ls cdc && cat cdc/REVISION"
```

- `git archive <SHA> infra/cdc/dev`는 그 커밋의 dev용 파일만 묶는다. 운영 검토안·PoC는 서버에 올리지 않는다.
- 비밀 디렉터리는 스택 폴더 밖(`/opt/beez/.cdc-secrets/`)이라 폴더를 바꿔도 남는다.
- 직전 것은 `cdc.old`로 하나 남겨 되돌릴 때 쓴다(`REVISION`에 커밋이 적혀 있음).

그다음 서버에서 다시 띄운다.

```bash
# 서버 (ssh beez). cdc 함수는 "처음 올리기" 3단계. 첫 줄은 "확인" 2)의 기준을 이번 올리기 직전으로 새로 잡는다
sudo ss -Htln | awk '{print $4}' | sort > /tmp/ss-before-cdc.txt
cdc up -d --wait && cdc ps --format '{{.Service}}\t{{.Status}}'
```

- compose 파일이 바뀌면 compose가 알아채 바뀐 컨테이너만 다시 만든다. 바인드 마운트하는 설정 파일은 connect의
  `connect-metrics.yaml` 하나(261)이고, 이것만 바뀌었으면 위 "지표 확인"대로 connect를 `--force-recreate`한다.
- **consumer를 켠 뒤(295 이후)에 kafka 컨테이너가 다시 만들어지면** 그동안 broker가 응답하지 않는다. 이미 떠 있는
  backend는 계속 healthy이고 처리는 복구 스캔이 30초 지연으로 이어 간다(계획 D7). 다만 그 사이 dev 머지 배포가
  겹치면 backend가 뜨지 못할 수 있으므로, Kafka를 바꾸는 올리기는 머지가 없는 시간에 한다(298 전까지).
- 이미지 버전을 올릴 때는 태그와 digest를 함께 바꾼다. digest는
  `docker buildx imagetools inspect <이미지>:<태그>`의 `Digest:`(멀티 아키텍처 인덱스)를 쓴다.
- 되돌리기: 서버에서 `cd /opt/beez && mv cdc cdc.bad && mv cdc.old cdc` 뒤 위 명령으로 다시 띄운다.

## 중지·내리기

S15P31A108-298부터는 consumer가 켜진 채 Kafka를 내려도 backend가 기동하고 healthy다. 분석은 복구 스캔이 이어
가고, Kafka가 돌아오면 consumer가 재시작 없이 다시 붙는다. 그래서 **consumer를 먼저 끄는 것은 필수가 아니다.**
다만 5분 넘게 내리면 `CdcConsumerDown`이 울리므로, 계획된 점검이면 1번처럼 consumer만 끄거나 알림을 일시 중지한다.
261부터는 같은 조건(consumer 켜짐)에서 `CdcKafkaUnreachable`·`CdcConnectDown`도 5분 뒤 함께 운다. 일시 중지는 이름을
`alertname=~"CdcConsumerDown|CdcKafkaUnreachable|CdcConnectDown|CdcConnectorNotRunning"`처럼 묶어 건다.

| 순서 | 할 일 | 비고 |
|---|---|---|
| 1 | (선택) backend consumer만 끄기: [cdc-connector.md](cdc-connector.md#0-준비-서버-bash-셸을-새로-열-때마다)의 `cdcenv true false` → 같은 SHA 재배포(cdc-connector.md 6) | 295 전이면 건너뛴다. **`BEEZ_CDC_INTAKE_ENABLED`(복구 스캔)는 켜 둔다** — 끄면 분석이 멈춘다(계획 §1). 점검이 끝나면 아래 "다시 켜기"로 되살린다 |
| 2 | 본 스택을 그대로 두면 `cdc stop`, 본 스택도 내릴 거면 **`cdc down`** | 아래 명령. 이유는 3번 |
| 3 | 본 스택을 내려야 할 때만 본 스택 `down` | `beez-cdc`가 떠 있으면 "has active endpoints"로 실패한다. `cdc stop`으로 멈추기만 했으면 `down`은 되지만 `beez_default`가 지워졌다가 새로 만들어져, 멈춘 컨테이너가 옛 네트워크를 가리킨 채 남는다. 그러면 `cdc start`·`cdc up` 모두 `network … not found`로 실패한다(로컬 실측). 그래서 2번에서 `cdc down`으로 컨테이너를 지운다 |

```bash
# 서버 (ssh beez)
cdc stop        # 멈추기만. 컨테이너·데이터 그대로. 본 스택을 내리지 않을 때만. 다시 띄울 때는 아래 "다시 켜기"
cdc down        # 컨테이너 삭제. 볼륨(토픽·Connect 설정·offset)은 남음
# cdc down -v   # 볼륨까지 삭제 — 아래 "볼륨을 지우면" 을 먼저 읽는다
```

- 커넥터가 등록된 뒤(295 이후)에 Connect를 오래 멈추면 PostgreSQL 슬롯이 WAL을 붙잡아 쌓인다. 오래 내릴 거면
  계획 §4 되돌리기 표대로 커넥터·슬롯까지 정리한다.
- 서버 재부팅 뒤에는 `restart: unless-stopped`로 Docker가 다시 띄운다. `cdc stop`으로 멈춘 것은 다시 띄우지 않는다.
  재부팅 때 backend가 Kafka보다 먼저 떠도 기동하고, consumer는 30초마다 다시 붙는다(298). backend가 healthy이고
  `beez_cdc_kafka_consumer_up` = 1인지 본다. 기동이 다른 이유로 실패하면 JVM이 exit 1로 끝나 Docker가 다시 띄운다(298 전에는
  MCP SDK 스레드 때문에 `running`으로 남아 스스로 회복하지 못했다).

### 다시 켜기 (점검 뒤 복귀)

위 순서로 멈췄다면 **역순으로 되살린다.** `cdc up`은 Kafka·Connect만 띄우고, 1번에서 consumer를 껐다면 꺼진 채로
남는다. 298부터 복구 스캔은 따로 켜져 있어 그동안에도 분석은 돌지만, Kafka 경로는 consumer를 손으로 다시 켜야 돌아온다.
1번을 건너뛰었다면 consumer는 Kafka가 뜨면 스스로 다시 붙는다(5번 건너뜀).

| 순서 | 할 일 | 확인 |
|---|---|---|
| 1 | 본 스택을 내렸다면 먼저 올린다(`beez_default`가 있어야 이 스택이 붙는다) | backend·postgres healthy |
| 2 | 기준 파일을 새로 잡은 뒤 `cdc up -d --wait`(stop·down 어느 쪽이든 같음, 아래 명령) | "확인" 1) 둘 다 healthy. `network … not found`가 나면 아래 첫 번째 주의 |
| 3 | 295 이후: 커넥터·task·슬롯 확인(아래 명령) | connector·task 모두 `RUNNING`, 슬롯 `active` = `t`. 아니면 여기서 멈추고 원인을 본다(계획 D6) |
| 4 | 켜기 직전 접수 상태를 적는다(아래 명령) | 켜는 순간 그동안 쌓인 PENDING을 복구 스캔이 한꺼번에 집어 AI를 호출한다(계획 D7) |
| 5 | 1번에서 껐다면 consumer 켜기: `cdcenv true true` → 같은 SHA 재배포 | [cdc-connector.md "6. consumer 켜기"](cdc-connector.md#6-consumer-켜기)의 2)·재배포를 그대로 한다. 명령을 손으로 고쳐 한 줄만 남기지 않는다 — consumer만 켜진 env는 backend 기동 거부(298) |
| 6 | 처리 재개 확인 | `beez_cdc_analysis_consumer_enabled` = 1, `beez_cdc_kafka_consumer_up` = 1, 4번에서 적은 PENDING이 줄어든다 |

```bash
# 서버 (ssh beez). cdc·kt 함수는 "처음 올리기" 3단계, PG·pgsql 은 cdc-postgres.md "0. 준비"
# 2) "확인" 2)의 기준을 띄우기 직전으로 새로 잡고 띄운다. up 은 stop 으로 멈춘 것도 다시 띄우고 healthy 까지 기다린다
sudo ss -Htln | awk '{print $4}' | sort > /tmp/ss-before-cdc.txt
cdc up -d --wait && cdc ps --format '{{.Service}}\t{{.Status}}'
# 3) 커넥터·task 상태. 커넥터가 없으면(295 전) {}
docker exec beez-cdc-connect-1 curl -s 'http://127.0.0.1:8083/connectors?expand=status'; echo
#    슬롯. Connect 가 다시 붙기까지 몇 초 걸릴 수 있다. retained 는 슬롯이 붙잡고 있는 WAL 양
#    </dev/null: pgsql 은 docker exec -i 라서 그냥 두면 함께 붙여 넣은 다음 줄을 입력으로 삼켜 실행하지 않는다
pgsql -c "SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots" </dev/null
# 4) 켜기 직전 접수 상태
pgsql -c "SELECT status, count(*) FROM published_change_analysis GROUP BY status ORDER BY status" </dev/null
```

- 2번에서 `network … not found`가 나면 `cdc stop` 뒤 본 스택을 내렸다 올린 경우다. `cdc up -d --wait --force-recreate`로
  컨테이너를 새 네트워크에 다시 만든다(로컬 실측으로 복구 확인). 데이터는 볼륨에 있어 그대로다.
- 295 전에는 멈출 때 1번(consumer)을 건너뛰었으므로 여기서도 1·2번까지만 한다.
- 3번에서 슬롯이 `active` = `f`로 남거나 `retained`가 1GB를 넘으면 consumer를 켜지 말고 계획 D6 "슬롯·Connect 감시"를
  따른다. 커넥터 없이 consumer만 켜면 토픽에 아무것도 오지 않아 연결 확인이 안 된다(계획 D7 "켜는 순서").

### 볼륨을 지우면

`beez-cdc_kafka-data`에는 접수 토픽, Connect 설정·offset·status 토픽이 함께 있다. 커넥터가 등록된 뒤(295 이후)에
이 볼륨만 지우면 Connect는 offset을 잃고, PostgreSQL 슬롯은 남은 채 커넥터가 사라진다. `infra/cdc/OPERATIONS.md`의
"slot, Connect offset, 내부 토픽을 독립적으로 삭제·초기화하지 않는다"에 걸린다. 그래서 **볼륨 삭제는 계획 §4
되돌리기 표 1~3번(consumer → 커넥터 → 슬롯)을 끝낸 뒤에만** 하고, 다시 올릴 때는 "처음 올리기" 4단계(접수 토픽)부터
다시 한다.

## 되돌리기

294만 되돌릴 때(295 전):

```bash
# 서버 (ssh beez)
cdc down -v && docker volume ls | grep beez-cdc    # 출력이 없으면 정리 끝
```

- 비밀 디렉터리 `/opt/beez/.cdc-secrets/`는 293 소관이라 여기서 지우지 않는다(293 되돌리기 순서를 따른다).
- 295 이후라면 계획 §4 되돌리기 표를 1번부터 순서대로 한다. 이 스택은 그 표의 4번이다.

## 백업 대상

| 대상 | 내용 | 백업 | 잃었을 때 |
|---|---|---|---|
| `infra/cdc/dev/` | 설정 전부 | 저장소가 정본 | 다시 올리기 |
| `/opt/beez/.cdc-secrets/database.properties` | 복제 계정 비밀번호 | 하지 않음 | [293 런북](cdc-postgres.md) "2. 비밀 파일 만들기"부터 다시 해 비밀번호를 새로 만들어 계정과 파일을 맞춤 |
| `beez-cdc_kafka-data` | 토픽 데이터, Connect 설정·offset | 하지 않음 | 메시지는 힌트일 뿐이라 복구 스캔이 처리를 이어 간다. 커넥터는 새 이름으로 다시 등록(계획 D6) |

## 한계

- **Connect 2.7.3은 Debezium 공식 지원표에 PG17이 없다.** 서버와 같은 pgvector PG17 이미지로 PoC 여러 개가 이
  버전으로 돈 것이 근거다(계획 D2, 11-05 전진 확인). 실제 커넥터 조합은 295 로컬 리허설이 처음이다.
- 단일 서버·단일 broker. broker가 죽으면 전달이 멈추고 복구 스캔이 받친다.
- 같은 망 컨테이너가 broker를 읽고 쓸 수 있다(계획 D3 "남는 위험").
- Connect 지표 포트 9404는 같은 망 컨테이너가 읽을 수 있다(위 "지표", 허용 목록으로 좁힘). 쓰기·설정 변경 경로는 없다.
- 비밀 파일 권한 함정(uid 1001)은 Windows 바인드 마운트에서 재현되지 않아 로컬에서 확인하지 못했다. 서버 "확인" 6)으로 본다.

## 로컬 확인 기록 (11-05, Docker Desktop 29.6.1)

`beez_default` 네트워크를 만들고 비밀 디렉터리에 더미 `database.properties`를 둔 뒤 서버와 같은 compose로 띄웠다.

```bash
# 로컬 PC (Git Bash, 저장소 루트). 끝나면 down -v 와 네트워크·더미 디렉터리 정리
docker network inspect beez_default >/dev/null 2>&1 || docker network create beez_default
CDC_SECRETS_DIR=<더미 디렉터리> docker compose -f infra/cdc/dev/compose.yaml up -d --wait
```

| 확인 | 결과 |
|---|---|
| 기동 | kafka 약 15초, connect 약 10초 뒤 healthy |
| 리슨 주소 | connect: `127.0.0.1:8083`만. kafka: `*:9092`, `127.0.0.1:9093`. `KAFKA_JMX_OPTS`를 채우기 전에는 두 JVM 모두 `*:<임의 포트>`(JMX RMI)가 열려 있었다. `-Dcom.sun.management.jmxremote=false`로는 닫히지 않았다(속성이 있기만 해도 커넥터를 연다) |
| 같은 망 probe 컨테이너 | `connect:8083`·`kafka:9093` 연결 거부, `kafka:9092` 연결됨 |
| 접수 토픽 | 손으로 생성, `--describe`가 파티션 1·복제 1·`retention.ms=604800000` |
| 자동 생성 차단 | 없는 토픽에 쓰면 `UNKNOWN_TOPIC_OR_PARTITION` 뒤 시간 초과, 토픽이 생기지 않음 |
| consumer group | 같은 망에서 `kafka:9092`로 group consume 성공, `__consumer_offsets` 50파티션·복제 1로 생성 |
| Connect 내부 토픽 | 셋 다 `cleanup.policy=compact`, 복제 1 |
| file config provider | 임시 PostgreSQL로 커넥터 설정 검증(`PUT …/config/validate`)에 `${file:…:password}`를 넣어 오류 0. 대조로 DB 비밀번호를 바꾸면 `password authentication failed`. 컨테이너 로그에 비밀번호 없음 |
| 비밀 디렉터리 없음 | 서버와 같은 리눅스 경로로 주면 `bind source path does not exist`로 기동 거부. (Docker Desktop의 Windows 경로는 빈 폴더를 만들어 버려 재현이 안 된다) |
| 재기동 | `down` → `up` 뒤 접수 토픽이 같은 TopicId로 남음 |
| 볼륨 | 띄운 뒤 새 볼륨은 `beez-cdc_kafka-data` 하나, `down` 뒤 남는 익명 볼륨 없음(tmpfs로 덮은 뒤) |
| 도구 힙 | `docker exec`로 돌린 `kafka-topics.sh`가 `-Xms512m -Xmx512m`을 물려받아 컨테이너 최대 사용량이 536→704MB로 오름. `kt`(`-Xmx128m`)로는 617→620MB |
| 메모리 | 띄운 직후 kafka 약 400MiB, connect 약 610MiB (상한 각 1GiB) |

## 서버 실행 기록

### 2026-11-05 처음 올리기 (전진 실행, 검증은 읽기 전용)

올린 커밋 `ad274ccc`(PR #156 브랜치). "처음 올리기" 1~4단계를 그대로 실행했다.

| 단계 | 결과 |
|---|---|
| 1. 비밀 디렉터리 | `drwxr-s--- root 1001`. `s`는 `/opt/beez`의 setgid가 이어진 것이라 안에 만드는 파일의 그룹이 1001이 된다(문제없음) |
| 2. 파일 올리기 | `/opt/beez/cdc`에 `compose.yaml`·`REVISION`. 처음에 서버 셸에서 쳐서 `not a git repository`로 멈췄다 — 이 명령은 **로컬 PC**에서 친다 |
| 3. 띄우기 | 이미지 받기 약 50초, kafka 12초·connect 23초 뒤 healthy. 볼륨 `beez-cdc_kafka-data` 생성 |
| 4. 접수 토픽 | 생성, `--describe` 파티션 1·복제 1·`retention.ms=604800000` |

확인(위 "확인" 절):

| 확인 | 결과 |
|---|---|
| 1) 상태 | kafka·connect healthy, `REVISION` = `ad274ccc` |
| 2) 호스트 새 리슨 주소 | 없음 |
| 3) backend·ai에서 | `connect:8083`·`kafka:9093` 연결 안 됨(backend `000`, ai `False`), `kafka:9092`만 연결 |
| 4) REST(컨테이너 안) | Connect 3.7.0 응답, 커넥터 `[]`(295 전). 컨테이너 안 리슨은 `127.0.0.1:8083`뿐, kafka는 `*:9092`·`127.0.0.1:9093` |
| 5) worker 설정 | `listeners=http://127.0.0.1:8083`, `config.providers=file` |
| 6) 비밀 파일 | 293이 쓴 `database.properties` `root:1001` `640`, Connect 사용자로 `test -r` 통과(내용은 보지 않음) |
| 7) 토픽 | 접수 토픽, Connect 내부 토픽 셋(파티션 1·25·5, compact·복제 1), `__consumer_offsets` |
| 메모리 | kafka 413MiB·connect 564MiB(상한 각 1GiB), 서버 사용 가능 약 12.4GB |
| 본 스택 | backend·ai·postgres healthy 그대로. 이름 없는(dangling) 볼륨 0 |

### 2026-11-06 지표(261) 로컬 확인

javaagent를 붙인 이 compose로 로컬 리허설과 같은 구성(pgvector `wal_level=logical`, 커넥터 등록)을 띄웠다.

| 확인 | 결과 |
|---|---|
| 리슨 | connect 컨테이너 안 `*:9404`(javaagent)가 새로 생기고 `127.0.0.1:8083`(REST)은 그대로. kafka는 변화 없음 |
| 메모리 | 커넥터 RUNNING 상태에서 javaagent 없이 638MiB, 붙인 뒤 641MiB |
| 지표 | `connect:9404/metrics`에 허용 목록 지표만(위 "지표" 표). pause하면 connector·task 모두 `status="paused"` |
| jar 없음 | (서버와 같은 리눅스 경로 기준) `create_host_path: false`라 `bind source path does not exist`로 connect가 뜨지 않는다 — 비밀 디렉터리와 같은 동작이라 따로 재현하지 않음 |
