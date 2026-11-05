#!/usr/bin/env bash
# BEEZ 백업 — PostgreSQL 덤프 먼저, SeaweedFS 원본 복사 나중 (S15P31A108-211).
#
# 사용: infra/backup/backup.sh <환경>
#   예) infra/backup/backup.sh dev
#
# 결과(BACKUP_ROOT, 기본 /var/backups/beez):
#   db/beez-<UTC 시각>.dump   pg_dump 커스텀 형식. 최근 KEEP_DUMPS 개만 남긴다
#   objects/<bucket>/<key>    원본 객체. 실행마다 새 객체만 더한다(삭제를 옮기지 않는다)
#   LAST_OK                   끝까지 성공한 마지막 실행의 덤프 이름(dump=<파일 이름>). 실패한 실행은 바꾸지 않는다.
#                             덤프 파일만으로는 성공 여부를 알 수 없다 — 복사·확인이 실패해도 덤프는 남는다
#                             (DB 백업으로는 쓸모가 있어 지우지 않는다). 서버 밖 보관·복원 시험은 이 파일을 기준으로 한다
#
# 순서가 이래야 하는 이유: 덤프가 가리키는 원본은 덤프 뒤에 복사해야 백업에 들어 있다. 승인·제출 원본은
# 지우지 않고(DocumentOriginalCleanupWorker), 복사가 삭제를 옮기지 않으므로 쓰기를 멈추지 않아도 된다.
# 예외와 서버 밖 보관은 docs/infra/seaweedfs.md "백업·복원".
#
# 키는 env 파일의 읽기 전용 백업 키(SEAWEEDFS_BACKUP_*)를 쓴다. 이 스크립트는 원본을 지울 수 없다.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 에서 시험할 때 /backup 같은 컨테이너 경로가 바뀌지 않게. 리눅스에서는 영향 없음

ENV_NAME=${1:?"사용: backup.sh <환경>"}
[[ $ENV_NAME =~ ^[a-z]+$ ]] || { echo "환경 이름이 잘못됨: $ENV_NAME" >&2; exit 2; }

ENV_FILE=${ENV_FILE:-/opt/beez/.env.$ENV_NAME}
BACKUP_ROOT=${BACKUP_ROOT:-/var/backups/beez}
PROJECT=${COMPOSE_PROJECT:-beez}   # compose 의 name. 네트워크 이름은 <PROJECT>_storage
KEEP_DUMPS=${KEEP_DUMPS:-14}
RCLONE_IMAGE=rclone/rclone:1.71.2@sha256:3103526c506266a9ecdf064efe99bf3677d92ef6407af124d8c56b4f49cbaa51

[[ -r $ENV_FILE ]] || { echo "env 파일을 읽을 수 없음: $ENV_FILE" >&2; exit 2; }
# env 파일을 source 하지 않는다(값이 셸 코드로 실행되지 않게). 필요한 줄만 꺼낸다
env_value() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }
BUCKET=$(env_value DOCUMENT_S3_BUCKET); BUCKET=${BUCKET:-beez-documents}

log() { echo "[backup $(date +%H:%M:%S)] $*"; }

# 덤프에는 계정·토큰 해시가 들어 있다. 만드는 파일을 주인만 읽게 한다
umask 077
mkdir -p "$BACKUP_ROOT/db" "$BACKUP_ROOT/objects"
# 한 번에 하나만 돈다(손 실행과 예약 실행이 겹치지 않게). PC 의 pull-backup.sh 도 같은 파일에 공유 잠금을 잡고
# 받는 동안 기다리게 한다 — 기다리지 않고 바로 끝내면 그 시각에 받고 있던 날은 백업이 통째로 빠진다
exec 9> "$BACKUP_ROOT/.backup.lock"
flock -w "${LOCK_WAIT:-1800}" 9 || { echo "다른 백업·받기가 ${LOCK_WAIT:-1800}초 넘게 진행 중이라 시작하지 않음" >&2; exit 3; }

pg=$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter label=com.docker.compose.service=postgres)
[[ -n $pg ]] || { echo "postgres 컨테이너가 없음 (project=$PROJECT)" >&2; exit 2; }

tmp=
trap '[[ -n $tmp ]] && rm -f "$tmp" || true' EXIT
# 신호로 끝나면 EXIT 처리가 돌지 않으므로 exit 로 바꿔 반쯤 쓴 덤프를 지운다 (deploy.sh 와 같은 방식)
trap 'exit 130' INT
trap 'exit 143' TERM

# 1) DB 덤프. 3)에서 이 덤프가 가리키는 문서 버전 원본이 다 복사됐는지 본다
stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump=$BACKUP_ROOT/db/beez-$stamp.dump
tmp=$dump.tmp
log "DB 덤프: $dump"
docker exec "$pg" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$tmp"
# 목차를 읽어 보아 잘린 파일이 아닌지 확인한 뒤 제 이름으로 바꾼다
docker exec -i "$pg" pg_restore -l > /dev/null < "$tmp"
mv "$tmp" "$dump"
tmp=

# 2) 원본 복사. copy 는 대상에만 있는 파일을 지우지 않는다(sync 를 쓰지 않는다).
#    --immutable: 같은 key 의 내용이 바뀌었으면 덮어쓰지 않고 실패한다. key 는 UUID 라 바뀔 일이 없다
#    --checksum: 수정 시각이 아니라 내용(MD5, SeaweedFS ETag)으로 같은지 본다. 수정 시각으로 보면 1초 미만 차이를
#      "바뀜"으로 보고 --immutable 이 매번 실패한다(2026-11-05 Docker Desktop 실측)
log "원본 복사: $BUCKET → $BACKUP_ROOT/objects/$BUCKET"
RCLONE_CONFIG_SRC_ACCESS_KEY_ID=$(env_value SEAWEEDFS_BACKUP_ACCESS_KEY) \
RCLONE_CONFIG_SRC_SECRET_ACCESS_KEY=$(env_value SEAWEEDFS_BACKUP_SECRET_KEY) \
docker run --rm --network "${PROJECT}_storage" --user "$(id -u):$(id -g)" \
    -v "$BACKUP_ROOT/objects:/backup" \
    -e RCLONE_CONFIG_SRC_TYPE=s3 -e RCLONE_CONFIG_SRC_PROVIDER=SeaweedFS \
    -e RCLONE_CONFIG_SRC_ENDPOINT=http://seaweedfs:8333 -e RCLONE_CONFIG_SRC_REGION=us-east-1 \
    -e RCLONE_CONFIG_SRC_ACCESS_KEY_ID -e RCLONE_CONFIG_SRC_SECRET_ACCESS_KEY \
    -e RCLONE_CONFIG=/dev/null -e XDG_CACHE_HOME=/tmp \
    "$RCLONE_IMAGE" copy "src:$BUCKET" "/backup/$BUCKET" --immutable --checksum --stats-one-line --stats-log-level NOTICE

# 3) 이 덤프가 가리키는 문서 버전 원본이 백업에 다 있는지 확인. 운영 DB 를 다시 읽지 않고 덤프에서 key 를 꺼낸다 —
#    덤프 뒤에 첫 제출로 key 가 바뀌면 운영 DB 는 새 key(아직 복사 전일 수 있음)를, 덤프는 옛 key 를 가리키기 때문이다.
#    덤프의 COPY 머리줄에서 storage_key 가 몇째 열인지 찾아 그 열만 뽑는다(열 순서를 코드에 박지 않는다)
keys=$(docker exec -i "$pg" pg_restore --data-only --table=document_version -f - < "$dump" | awk -F '\t' '
    /^COPY [^ ]*document_version \(/ {
        cols = $0; sub(/^[^(]*\(/, "", cols); sub(/\).*$/, "", cols)
        n = split(cols, c, ", "); col = 0
        for (i = 1; i <= n; i++) if (c[i] == "storage_key") col = i
        if (col == 0) { print "storage_key 열을 찾지 못함" > "/dev/stderr"; exit 2 }
        copying = 1; next
    }
    copying && /^\\\.$/ { copying = 0; next }   # COPY 데이터 끝 표시 줄 "\."
    copying { print $col }')
versions=$(printf '%s' "$keys" | grep -c . || true)
missing=0
while read -r key; do
    [[ -z $key ]] && continue
    [[ -f $BACKUP_ROOT/objects/$BUCKET/$key ]] || { log "백업에 없는 원본: $key"; missing=$((missing + 1)); }
done <<< "$keys"
if (( missing > 0 )); then
    log "실패: 덤프가 가리키는 문서 버전 원본 ${missing}개가 백업에 없다 (LAST_OK 는 그대로 둔다)"
    exit 1
fi

# 4) 오래된 덤프 정리. 원본(objects)은 지우지 않는다 — 남은 어느 덤프로 복원해도 원본이 있어야 한다
# shellcheck disable=SC2012
ls -1t "$BACKUP_ROOT"/db/beez-*.dump | tail -n +$((KEEP_DUMPS + 1)) | while read -r old; do
    rm -f "$old" && log "오래된 덤프 삭제: $old"
done

# 5) 성공 표시. 임시 파일에 쓰고 이름을 바꿔, 받는 쪽이 반쯤 쓴 파일을 읽지 않게 한다
printf 'dump=%s\n' "$(basename "$dump")" > "$BACKUP_ROOT/LAST_OK.tmp"
mv "$BACKUP_ROOT/LAST_OK.tmp" "$BACKUP_ROOT/LAST_OK"

log "완료: 덤프 $(du -h "$dump" | cut -f1), 원본 $(find "$BACKUP_ROOT/objects/$BUCKET" -type f | wc -l)개 (덤프의 문서 버전 ${versions}개 확인)"
