#!/usr/bin/env bash
# BEEZ 백업 복원 시험 (S15P31A108-211).
#
# 백업 한 벌(덤프 + 원본)을 임시 postgres·SeaweedFS 에 복원하고, DB 의 document_version.storage_key 로
# 원본을 내려받아 SHA-256 이 content_hash 와 같은지 본다. 운영 컨테이너·volume 은 건드리지 않는다.
# Docker 만 있으면 어디서나 돈다(서버, 백업을 내려받은 PC 의 Git Bash).
#
# 사용: infra/backup/restore-check.sh <백업 폴더> [덤프 파일]
#   예) infra/backup/restore-check.sh ~/beez-backup
#   덤프를 고르지 않으면 <백업 폴더>/LAST_OK 의 덤프(끝까지 성공한 마지막 백업)를 쓰고, LAST_OK 가 없으면
#   db 의 가장 최근 덤프를 쓴다. 가장 최근 덤프는 원본 복사가 실패한 실행의 것일 수 있다.
#
# 종료 코드: 0 = 모든 문서 버전 원본 일치, 1 = 빠졌거나 다른 원본이 있음·복원한 DB 를 읽지 못함(복원 시험 실패), 2 = 사용법·환경 오류
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 에서 /w 같은 컨테이너 경로가 바뀌지 않게. 리눅스에서는 영향 없음

ROOT=${1:?"사용: restore-check.sh <백업 폴더> [덤프 파일]"}
ok_dump=$(sed -n 's/^dump=//p' "$ROOT/LAST_OK" 2> /dev/null || true)
# 덤프 이름은 backup.sh 가 만든 beez-<UTC 시각>.dump 뿐이라 이름 정렬이 곧 시각 순서다
DUMP=${2:-}
if [[ -z $DUMP && -n $ok_dump ]]; then
    if [[ -f $ROOT/db/$ok_dump ]]; then
        DUMP=$ROOT/db/$ok_dump
    else
        echo "경고: LAST_OK 의 덤프($ok_dump)가 없어 db 의 가장 최근 덤프를 쓴다(실패한 실행의 것일 수 있다)" >&2
    fi
fi
# shellcheck disable=SC2012
DUMP=${DUMP:-$(ls -1 "$ROOT"/db/beez-*.dump 2> /dev/null | sort | tail -n 1)}
BUCKET=${DOCUMENT_S3_BUCKET:-beez-documents}
OBJECTS=$ROOT/objects/$BUCKET
[[ -f $DUMP ]] || { echo "덤프가 없음: ${DUMP:-$ROOT/db/beez-*.dump}" >&2; exit 2; }
[[ -d $ROOT/objects ]] || { echo "백업 폴더가 아님(objects 없음): $ROOT" >&2; exit 2; }
# objects/<bucket> 이 없으면 문서 0건일 때의 백업이다(빈 폴더는 PC 사본으로 옮겨지지 않는다). 빈 폴더로 대신한다
empty_objects=
if [[ ! -d $OBJECTS ]]; then
    empty_objects=$(mktemp -d)
    OBJECTS=$empty_objects
fi

# 이미지는 운영과 같은 digest 를 compose.yaml 에서 읽는다. 버전을 올릴 때 이 파일을 따로 고치지 않는다
COMPOSE_FILE=$(cd "$(dirname "$0")/.." && pwd)/compose.yaml
PG_IMAGE=$(grep -m1 -oE 'pgvector/pgvector@sha256:[0-9a-f]{64}' "$COMPOSE_FILE")
SW_IMAGE=$(grep -m1 -oE 'chrislusf/seaweedfs:[0-9.]+@sha256:[0-9a-f]{64}' "$COMPOSE_FILE")
RCLONE_IMAGE=rclone/rclone:1.71.2@sha256:3103526c506266a9ecdf064efe99bf3677d92ef6407af124d8c56b4f49cbaa51
CURL_IMAGE=curlimages/curl:8.10.1@sha256:d9b4541e214bcd85196d6e92e2753ac6d0ea699f0af5741f8c6cccbfcf00ef4b
[[ -n $PG_IMAGE && -n $SW_IMAGE ]] || { echo "compose.yaml 에서 이미지 digest 를 찾지 못함" >&2; exit 2; }

N=beez-restore-check-$$
AK=$(openssl rand -hex 12)   # 임시 SeaweedFS 의 관리 키. 이 시험이 끝나면 컨테이너와 함께 사라진다
SK=$(openssl rand -hex 24)
log() { echo "[restore-check $(date +%H:%M:%S)] $*"; }

cleanup() {
    docker rm -f -v "$N-pg" "$N-sw" > /dev/null 2>&1 || true
    docker network rm "$N" > /dev/null 2>&1 || true
    [[ -n $empty_objects ]] && rm -rf "$empty_objects"
    true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log "덤프: $DUMP"
log "원본: $OBJECTS ($(find "$OBJECTS" -type f | wc -l)개)"
docker network create "$N" > /dev/null
docker run -d --name "$N-pg" --network "$N" -e POSTGRES_USER=beez -e POSTGRES_PASSWORD=restore -e POSTGRES_DB=beez \
    "$PG_IMAGE" > /dev/null
docker run -d --name "$N-sw" --network "$N" -e AWS_ACCESS_KEY_ID="$AK" -e AWS_SECRET_ACCESS_KEY="$SK" \
    -e S3_BUCKET="$BUCKET" "$SW_IMAGE" mini -dir=/data -master.telemetry=false > /dev/null

for _ in $(seq 1 60); do docker exec "$N-pg" pg_isready -U beez -d beez -q 2> /dev/null && break; sleep 1; done
for _ in $(seq 1 60); do docker logs "$N-sw" 2>&1 | grep -q 'created bucket\|bucket .* already exists' && break; sleep 1; done

# 1) DB 복원. 소유자·권한은 임시 DB 사용자로 바꾼다
log "DB 복원"
docker exec -i "$N-pg" pg_restore -U beez -d beez --no-owner --no-privileges --exit-on-error < "$DUMP"

# 2) 원본 올리기. 백업 폴더를 그대로 넘기면 PC·서버 경로 차이가 생기므로 tar 로 흘려 넣는다
log "원본 올리기"
tar -C "$OBJECTS" -cf - . | docker run -i --rm --network "$N" --entrypoint sh \
    -e RCLONE_CONFIG_DST_TYPE=s3 -e RCLONE_CONFIG_DST_PROVIDER=SeaweedFS \
    -e RCLONE_CONFIG_DST_ENDPOINT="http://$N-sw:8333" -e RCLONE_CONFIG_DST_REGION=us-east-1 \
    -e RCLONE_CONFIG_DST_ACCESS_KEY_ID="$AK" -e RCLONE_CONFIG_DST_SECRET_ACCESS_KEY="$SK" \
    -e RCLONE_CONFIG=/dev/null -e XDG_CACHE_HOME=/tmp \
    "$RCLONE_IMAGE" -c "mkdir /w && tar -xf - -C /w && rclone copy /w dst:$BUCKET --stats-one-line --stats-log-level NOTICE"

# 3) storage_key 로 내려받아 해시 대조. content_hash 형식은 "sha256:<16진수 64자>"(DocumentInputValidator)
log "문서 버전 원본 대조"
# DB 조회를 먼저 끝내고 결과를 넘긴다. 파이프로 바로 이으면 조회 실패도 "원본이 DB 와 맞지 않는다"로 보인다(check-live.sh 와 같음)
rows=$(docker exec "$N-pg" psql -U beez -d beez -XAt -F ' ' -v ON_ERROR_STOP=1 \
    -c 'SELECT id, storage_key, content_hash FROM document_version ORDER BY id') ||
    { log "실패: 복원한 DB 조회 실패 — 덤프 복원이 끝나지 않았거나 스키마가 다르다(복원 시험 실패)"; exit 1; }
# 0건이면 빈 줄 하나도 넘기지 않는다(빈 줄은 key 없는 "없음" 한 건으로 세어진다)
{ [[ -n $rows ]] && printf '%s\n' "$rows"; true; } \
  | docker run -i --rm --network "$N" --entrypoint sh -e AK="$AK" -e SK="$SK" -e EP="http://$N-sw:8333/$BUCKET" \
    "$CURL_IMAGE" -c '
      ok=0; bad=0
      while read -r id key hash; do
        if ! curl -sf -o /tmp/o --aws-sigv4 aws:amz:us-east-1:s3 --user "$AK:$SK" "$EP/$key"; then
          echo "  없음   version=$id key=$key"; bad=$((bad + 1)); continue
        fi
        got="sha256:$(sha256sum /tmp/o | cut -d " " -f1)"
        if [ "$got" = "$hash" ]; then ok=$((ok + 1)); else echo "  불일치 version=$id key=$key"; bad=$((bad + 1)); fi
      done
      echo "  일치 $ok개, 문제 $bad개"
      [ "$bad" -eq 0 ]' || { log "실패: 복원한 원본이 DB 와 맞지 않는다"; exit 1; }

# 작업본(document_working_copy)의 원본은 덮어쓰면 지워지는 임시 파일이고 본문은 DB(content_text)에 있다.
# 백업 도중 편집되면 빠질 수 있어 경고만 한다
missing_wc=$(docker exec "$N-pg" psql -U beez -d beez -XAt -c \
    'SELECT storage_key FROM document_working_copy WHERE storage_key IS NOT NULL' \
  | while read -r key; do [[ -f $OBJECTS/$key ]] || echo "$key"; done | wc -l)
(( missing_wc == 0 )) || log "경고: 작업본 원본 ${missing_wc}개가 백업에 없다(본문은 DB 에 있음)"

log "통과: $(basename "$DUMP")"
