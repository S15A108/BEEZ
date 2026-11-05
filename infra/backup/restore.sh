#!/usr/bin/env bash
# 운영 복원 — 서버 백업에서 문서 원본(SeaweedFS)이나 DB 까지 되살린다 (S15P31A108-211).
#
# 사용(서버):
#   infra/backup/restore.sh <환경> objects --yes            # 원본만: 백업 폴더 → bucket. 이미 있는 원본은 건너뛴다
#   infra/backup/restore.sh <환경> all [덤프 파일] --yes    # DB 를 덤프 시점으로 + 원본. 덤프 기본값은 LAST_OK 의 것
# --yes 가 없으면 할 일만 보여 주고 아무것도 바꾸지 않는다.
# 종료 코드: 0 = 완료(미리보기 포함), 1 = 실패(사전 검증·복원 단계), 2 = 사용법·환경 오류(env 파일 등) — 다른 백업 스크립트와 같다
#
# all 의 순서: 백업·배포 잠금 → 사전 검증(덤프가 가리키는 원본이 백업 폴더나 bucket 에 다 있는가. 없으면 아무것도
#   바꾸지 않고 멈춤) → backend 멈춤 → DB 를 지우고 빈 DB 로 다시 만듦 → pg_restore → 원본 올리기 → backend 켬
#   - 빈 DB 에 복원한다. --clean 으로 덮어쓰면 덤프 뒤 마이그레이션이 만든 테이블이 남고 Flyway 이력만 덤프 시점으로
#     돌아가, backend 를 켤 때 Flyway 가 같은 마이그레이션을 다시 돌리다 "already exists" 로 멈춘다
#   - 배포 잠금(deploy.sh 와 같은 파일)을 스크립트가 끝날 때까지 쥔다. 도중에 자동 배포가 backend 를 켜지 못하게
#   - 중간에 실패하면 backend 를 켜지 않고 멈춘다. DB 가 비었거나 반쯤 복원된 채로 켜면 Flyway 가 빈 스키마를 새로
#     만들어 "데이터가 없는 정상 서비스"처럼 보이기 때문이다. 원인을 고쳐 다시 돌린다(DB 를 다시 지우고 처음부터 한다).
#     스크립트가 끝나면 배포 잠금도 풀리므로, 그사이 dev 머지가 있으면 자동 배포가 backend 를 켠다 — 복원하는 동안은
#     팀에 머지를 멈춰 달라고 알린다(docs/infra/operations.md 8. 백업·복원)
#
# 원본 올리기는 관리(bootstrap) 키로 rclone copy --immutable --checksum 을 쓴다. bucket 의 객체를 지우지 않고,
# 같은 key 의 내용이 다르면 덮어쓰지 않고 실패한다.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 에서 시험할 때 컨테이너 경로가 바뀌지 않게. 리눅스에서는 영향 없음
# root 로 돌면 백업 잠금 파일이 root 소유로 생겨 ubuntu crontab 의 백업이 매일 실패한다(backup.sh 와 같은 이유)
[[ $(id -u) != 0 ]] || { echo "root(sudo)로 실행하지 않는다. docker 그룹의 ubuntu 로 실행한다" >&2; exit 2; }

usage() { echo "사용: restore.sh <환경> objects --yes | restore.sh <환경> all [덤프 파일] --yes" >&2; exit 2; }
ENV_NAME=${1:-}; MODE=${2:-}
[[ $ENV_NAME =~ ^[a-z]+$ ]] || usage
[[ $MODE == objects || $MODE == all ]] || usage
shift 2
DUMP=; YES=0
for arg in "$@"; do
    case $arg in
        --yes) YES=1 ;;
        -*) usage ;;
        *) [[ $MODE == all && -z $DUMP ]] || usage; DUMP=$arg ;;
    esac
done

ENV_FILE=${ENV_FILE:-/opt/beez/.env.$ENV_NAME}
BACKUP_ROOT=${BACKUP_ROOT:-/var/backups/beez}
PROJECT=${COMPOSE_PROJECT:-beez}
DEPLOY_LOCK=${DEPLOY_LOCK:-/var/www/beez/.deploy.lock}
RCLONE_IMAGE=rclone/rclone:1.71.2@sha256:3103526c506266a9ecdf064efe99bf3677d92ef6407af124d8c56b4f49cbaa51

log() { echo "[restore $(date +%H:%M:%S)] $*"; }
# stderr 로 낸다. $(cid …) 안에서 불려도 메시지가 변수에 삼켜지지 않게
fail() { log "실패: $*" >&2; exit 1; }
# 환경 오류는 2 로 끝낸다(backup.sh·check-live.sh 와 같은 종료 코드)
env_fail() { log "실패: $*" >&2; exit 2; }
[[ -r $ENV_FILE ]] || env_fail "env 파일을 읽을 수 없음: $ENV_FILE"
# env 파일은 배포(compose)와 같은 규칙으로 읽는다(따옴표·인라인 주석·CRLF). check-port.sh 의 envs() 와 같은 방식.
# compose 오류 문구에는 값이 섞일 수 있어 원문을 내보내지 않는다
ENV_ALL=$(printf 'services: {}\n' | docker compose -f - --env-file "$ENV_FILE" config --environment 2> /dev/null) ||
    env_fail "env 파일을 compose 로 해석하지 못함(형식 오류일 수 있어 문구는 숨김). 서버에서 docker compose 로 직접 확인한다"
env_value() { printf '%s\n' "$ENV_ALL" | sed -n "s/^$1=//p" | tail -n 1; }
BUCKET=$(env_value DOCUMENT_S3_BUCKET); BUCKET=${BUCKET:-beez-documents}
OBJECTS=$BACKUP_ROOT/objects/$BUCKET
[[ -d $BACKUP_ROOT/objects ]] || fail "백업 폴더가 아님(objects 없음): $BACKUP_ROOT"
# objects/<bucket> 이 없으면 문서 0건일 때의 백업이다(빈 폴더는 PC 사본·옛 backup.sh 에서 빠질 수 있다). 원본 0개로 본다
n_objects=0
[[ -d $OBJECTS ]] && n_objects=$(find "$OBJECTS" -type f | wc -l)

cid() {
    local id
    id=$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$1")
    [[ $(printf '%s\n' "$id" | grep -c .) == 1 ]] || fail "$1 컨테이너를 하나로 찾지 못함 (project=$PROJECT)"
    printf '%s' "$id"
}

if [[ $MODE == all ]]; then
    if [[ -z $DUMP ]]; then
        ok=$(sed -n 's/^dump=//p' "$BACKUP_ROOT/LAST_OK" 2> /dev/null || true)
        [[ -n $ok ]] || fail "LAST_OK 가 없음. 덤프 파일을 직접 고른다"
        DUMP=$BACKUP_ROOT/db/$ok
    fi
    [[ -f $DUMP ]] || fail "덤프가 없음: $DUMP"
fi

# 관리(bootstrap) 키로 rclone 을 storage 망에서 돌린다. 키 값은 -e 이름으로만 넘긴다. 앞에 -v <폴더:경로> 를 주면 붙인다
rclone_admin() {
    local mount=()
    if [[ ${1:-} == -v ]]; then mount=(-v "$2"); shift 2; fi
    RCLONE_CONFIG_DST_ACCESS_KEY_ID=$(env_value SEAWEEDFS_BOOTSTRAP_ACCESS_KEY) \
    RCLONE_CONFIG_DST_SECRET_ACCESS_KEY=$(env_value SEAWEEDFS_BOOTSTRAP_SECRET_KEY) \
    docker run --rm --network "${PROJECT}_storage" "${mount[@]}" \
        -e RCLONE_CONFIG_DST_TYPE=s3 -e RCLONE_CONFIG_DST_PROVIDER=SeaweedFS \
        -e RCLONE_CONFIG_DST_ENDPOINT=http://seaweedfs:8333 -e RCLONE_CONFIG_DST_REGION=us-east-1 \
        -e RCLONE_CONFIG_DST_ACCESS_KEY_ID -e RCLONE_CONFIG_DST_SECRET_ACCESS_KEY \
        -e RCLONE_CONFIG=/dev/null -e XDG_CACHE_HOME=/tmp \
        "$RCLONE_IMAGE" "$@"
}

put_objects() {
    if (( n_objects == 0 )); then
        # all 은 사전 검증이 덤프가 가리키는 원본이 다 있는지 먼저 확인했다. 여기서 0개면 올릴 것이 없는 것이다
        log "올릴 원본 0개 — 건너뜀"
        return 0
    fi
    log "원본 올리기"
    rclone_admin -v "$OBJECTS:/backup:ro" copy /backup "dst:$BUCKET" --immutable --checksum \
        --stats-one-line --stats-log-level NOTICE || fail "원본 올리기 실패(위 rclone 메시지)"
}

# 사전 검증(all) — 읽기만 한다. 미리보기(--yes 없음)에서도, 실제 복원에서는 backend 를 멈추기 전에 돈다.
#   1) 덤프가 가리키는 문서 버전 원본이 백업 폴더나 지금 bucket 에 다 있는가. 원본 폴더가 없거나 비어 있는 것만으로는
#      "문서 0건"인지 "원본이 빠진 백업"인지 알 수 없다(2026-11-05 hyunneee 리뷰). bucket 에만 있는 원본은 허용한다(DB 만 되돌리는 경우).
#      덤프의 COPY 머리줄에서 storage_key 열을 찾아 뽑는다(backup.sh 3) 과 같은 방법)
#   2) 백업 폴더의 원본을 올릴 때 bucket 의 같은 key 와 내용이 다르지 않은가(copy --dry-run --immutable).
#      다르면 실제 올리기가 --immutable 로 실패하는데, 그때는 이미 DB 를 지운 뒤라 서비스가 멈춘 채로 남는다
precheck() {
    local pg=$1 keys key in_bucket not_in_folder=() missing=()
    log "사전 검증: 덤프가 가리키는 원본이 백업 폴더나 bucket 에 있는지, 같은 key 의 내용이 다르지 않은지"
    keys=$(docker exec -i "$pg" pg_restore --data-only --table=document_version -f - < "$DUMP" | awk -F '\t' '
        /^COPY [^ ]*document_version \(/ {
            cols = $0; sub(/^[^(]*\(/, "", cols); sub(/\).*$/, "", cols)
            n = split(cols, c, ", "); col = 0
            for (i = 1; i <= n; i++) if (c[i] == "storage_key") col = i
            if (col == 0) { print "storage_key 열을 찾지 못함" > "/dev/stderr"; exit 2 }
            copying = 1; next
        }
        copying && /^\\\.$/ { copying = 0; next }   # COPY 데이터 끝 표시 줄 "\."
        copying { print $col }') || fail "덤프에서 storage_key 를 읽지 못함. 아무것도 바꾸지 않았다"
    while read -r key; do
        [[ -z $key ]] && continue
        [[ -f $OBJECTS/$key ]] || not_in_folder+=("$key")
    done <<< "$keys"
    if (( ${#not_in_folder[@]} > 0 )); then
        in_bucket=$(rclone_admin lsf "dst:$BUCKET" --files-only) ||
            fail "bucket 목록을 읽지 못함(위 rclone 메시지). 아무것도 바꾸지 않았다"
        for key in "${not_in_folder[@]}"; do
            grep -qxF -- "$key" <<< "$in_bucket" || missing+=("$key")
        done
    fi
    if (( ${#missing[@]} > 0 )); then
        fail "덤프가 가리키는 원본 ${#missing[@]}개가 백업 폴더와 bucket 어디에도 없음(예: ${missing[0]}). 아무것도 바꾸지 않았다. 원본이 있는 백업(PC 사본 등)을 서버로 보낸 뒤 다시 실행한다"
    fi
    if (( n_objects > 0 )); then
        rclone_admin -v "$OBJECTS:/backup:ro" copy /backup "dst:$BUCKET" --dry-run --immutable --checksum \
            --stats-log-level NOTICE ||
            fail "백업 원본과 bucket 의 같은 key 내용이 다름(위 rclone 메시지). 아무것도 바꾸지 않았다. 어느 쪽이 맞는지 확인한다"
    fi
    log "사전 검증 통과: 덤프의 문서 버전 원본 $(printf '%s' "$keys" | grep -c . || true)개(백업 폴더에 없고 bucket 에만 있는 것 ${#not_in_folder[@]}개)"
}

log "환경 $ENV_NAME, 할 일: $([[ $MODE == all ]] && echo "DB 를 $(basename "$DUMP") 시점으로 되돌리고(지금 DB 는 지움) 원본 올리기" || echo "원본 올리기")"
log "원본 백업: $OBJECTS (${n_objects}개) → bucket $BUCKET"
if (( ! YES )); then
    [[ $MODE == all ]] && precheck "$(cid postgres)"
    log "바꾸지 않음. 진행하려면 끝에 --yes 를 붙인다"
    exit 0
fi

# 서버에서 백업 데이터를 쓰거나 읽어 판정하는 작업(backup.sh·이 스크립트)은 모두 이 잠금 하나를 잡는다.
# 복원 중에 예약 백업이 돌면 비었거나 반쯤 복원된 DB 를 덤프해 LAST_OK 로 기록할 수 있다.
# 잠금 순서는 늘 백업 잠금 → 배포 잠금이다(배포 잠금을 쥔 채 백업 잠금을 기다리는 곳이 없어 서로 막히지 않는다)
exec 7> "$BACKUP_ROOT/.backup.lock"
flock -w 1800 7 || fail "서버 백업이 30분 넘게 진행 중. 끝난 뒤 다시 실행한다"
log "백업 잠금 잡음(이 스크립트가 끝나면 풀림)"

if [[ $MODE == objects ]]; then
    put_objects
    log "완료. 확인: check-live.sh $ENV_NAME"
    exit 0
fi

# ---- all ----
[[ -e $DEPLOY_LOCK ]] || fail "배포 잠금 파일이 없음: $DEPLOY_LOCK"
exec 8< "$DEPLOY_LOCK"
flock -w 300 8 || fail "다른 배포가 5분 넘게 진행 중. 끝난 뒤 다시 실행한다"
log "배포 잠금 잡음(이 스크립트가 끝나면 풀림)"

pg=$(cid postgres)
backend=$(cid backend)
# 받아들인 한계: 검증과 아래 backend 멈춤 사이 몇 초 동안 정리 워커가 "덤프는 가리키지만 지금 DB 는 참조하지 않는" 원본을
# bucket 에서 지울 수 있다(bucket 에만 있는 옛 key 일 때). 막으려면 backend 를 먼저 멈춰야 해서 "실패하면 아무것도 바꾸지
# 않음"을 잃는다. 드문 조건이라 받아들인다(docs/infra/operations.md 8. 전체 복원)
precheck "$pg"

log "backend 멈춤(웹 사용자는 로그아웃된다)"
docker stop "$backend" > /dev/null

log "DB 를 지우고 빈 DB 로 다시 만듦"
# 접속 중인 세션(모니터링 exporter 등)은 FORCE 로 끊는다. 지우는 동안은 관리용 postgres DB 에 붙는다
docker exec "$pg" sh -c 'psql -U "$POSTGRES_USER" -d postgres -XAq -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\" WITH (FORCE)" -c "CREATE DATABASE \"$POSTGRES_DB\""' ||
    fail "DB 다시 만들기 실패. backend 는 멈춘 채로 둔다"

log "덤프 복원: $(basename "$DUMP")"
docker exec -i "$pg" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' < "$DUMP" ||
    fail "pg_restore 실패. backend 는 멈춘 채로 둔다(이대로 켜면 빈 스키마가 새로 생긴다). 원인을 고쳐 다시 실행한다"

put_objects

log "backend 켬"
docker start "$backend" > /dev/null
for _ in $(seq 1 48); do
    state=$(docker inspect -f '{{.State.Health.Status}}' "$backend" 2> /dev/null || echo unknown)
    [[ $state == healthy ]] && break
    sleep 5
done
[[ $state == healthy ]] || fail "backend 가 4분 안에 healthy 가 되지 않음(지금 $state). 로그: docker logs --tail 80 $backend"
log "완료: backend healthy. 확인: check-live.sh $ENV_NAME"
