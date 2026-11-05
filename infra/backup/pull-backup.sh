#!/usr/bin/env bash
# 서버 백업을 PC 로 당겨 온다 — 서버 밖 보관 (S15P31A108-211).
#
# 사용(PC Git Bash): infra/backup/pull-backup.sh [받을 폴더] [--check]
#   예) infra/backup/pull-backup.sh                       # ~/beez-backup/dev 로 받기
#       infra/backup/pull-backup.sh ~/beez-backup/dev --check   # 받은 뒤 마지막 성공 덤프로 복원 시험(restore-check.sh, Docker 필요)
#
# 서버의 backup.sh 가 만든 BACKUP_ROOT(기본 /var/backups/beez)에서 PC 에 아직 없는 파일만 받는다.
#   - 지우지 않는다: 서버에서 사라진 덤프(14개 보존)·원본도 PC 에는 남는다
#   - 덮어쓰지 않는다: 이미 받은 파일은 다시 받지 않는다. 원본 key 는 UUID 라 내용이 바뀌지 않는다
#   - 받기가 끝까지 성공했을 때만 PC 폴더로 옮긴다: 임시 폴더(.incoming)에 풀고, 중간에 끊기면 버린다.
#     바로 풀면 끊긴 자리의 잘린 파일이 남고, 이름이 이미 있으니 다음부터 다시 받지 않는다
#   - 서버 백업을 기다리지 않는다(잠금 없음). LAST_OK 를 목록보다 먼저 떠 두므로, 받은 LAST_OK 가 가리키는 덤프와 그
#     원본은 백업 중이어도 이미 다 있다(LAST_OK 는 복사·확인이 끝난 뒤에 쓰이고 원본은 지우지 않는다). 백업 중에 생긴
#     덤프·원본은 다음 받기 때 온다. 잠금을 쥐고 받으면 느린 받기가 그날 백업을 막는다
#   - LAST_OK(끝까지 성공한 마지막 서버 백업의 덤프 이름)는 매번 새로 받는다. 신선도·복원 시험은 이것을 기준으로 한다 —
#     복사가 실패한 날도 덤프 파일은 생기므로 덤프 시각만 보면 실패를 놓친다
#
# 종료 코드: 0 = 받음(새 파일 0개 포함), 1 = 마지막 성공 백업이 36시간보다 오래됐거나 없음(서버 백업이 멈췄거나 실패 중),
#            2 = 사용법·접속 오류, 그 밖 = restore-check.sh 실패
set -euo pipefail

DEST=${HOME}/beez-backup/dev
CHECK=0
for arg in "$@"; do
    case $arg in
        --check) CHECK=1 ;;
        -*) echo "모르는 옵션: $arg" >&2; exit 2 ;;
        *) DEST=$arg ;;
    esac
done
HOST=${BEEZ_SSH_HOST:-beez}
REMOTE=${REMOTE_BACKUP_ROOT:-/var/backups/beez}
STALE_HOURS=${STALE_HOURS:-36}
STAGE=$DEST/.incoming

log() { echo "[pull-backup $(date +%H:%M:%S)] $*"; }
mkdir -p "$DEST/db" "$DEST/objects"

# 서버에서 도는 부분. PC 가 가진 파일 목록을 표준입력으로 받아, 없는 것과 먼저 떠 둔 LAST_OK 를 tar 로 내보낸다.
# rclone 이 쓰는 중인 *.partial 과 덤프 임시 파일(*.dump.tmp)은 보내지 않는다
# shellcheck disable=SC2016  # 서버에서 펼칠 변수다
remote_script='
set -euo pipefail
cd "$1"
snap=$(mktemp -d)
trap "rm -rf \"$snap\"" EXIT
# LAST_OK 를 파일 목록보다 먼저 떠 둔다. 목록을 만든 뒤에 읽으면 그사이 끝난 백업의 LAST_OK 가 목록에 없는 덤프를 가리킬 수 있다
if [ -f LAST_OK ]; then cp LAST_OK "$snap/LAST_OK"; fi
LC_ALL=C comm -23 \
    <({ find db -type f -name "beez-*.dump"; find objects -type f ! -name "*.partial"; } | LC_ALL=C sort) \
    <(LC_ALL=C sort) > "$snap/list"
extra=()
if [ -f "$snap/LAST_OK" ]; then extra=(-C "$snap" LAST_OK); fi
# --ignore-failed-read: 목록을 만든 뒤 백업이 오래된 덤프를 정리했으면 그 파일만 건너뛴다(PC 에는 보통 이미 있다).
# 보낼 것이 없어도 빈 tar 를 보낸다(PC 의 tar 가 빈 입력을 오류로 본다)
tar -cf - --ignore-failed-read --verbatim-files-from -T "$snap/list" "${extra[@]}"
'

# 지난번에 끊긴 받기가 남긴 것은 버린다
rm -rf "${STAGE:?}"
mkdir -p "$STAGE"
before=$(find "$DEST/db" "$DEST/objects" -type f | wc -l)
log "받기: $HOST:$REMOTE → $DEST"
# shellcheck disable=SC2029  # 서버 스크립트와 경로를 PC 에서 %q 로 감싸 넘긴다(의도)
if ! (cd "$DEST" && find db objects -type f | LC_ALL=C sort) \
    | ssh "$HOST" "bash -c $(printf '%q' "$remote_script") _ $(printf '%q' "$REMOTE")" \
    | tar -C "$STAGE" -xf -; then
    rm -rf "${STAGE:?}"
    echo "받기 실패 (위 메시지 확인). 받은 것은 버렸고 PC 폴더는 그대로다" >&2
    exit 2
fi

# 끝까지 받은 것만 옮긴다. 원본·덤프는 있는 파일을 덮어쓰지 않는다(mv -n)
while IFS= read -r -d '' f; do
    rel=${f#"$STAGE"/}
    mkdir -p "$DEST/$(dirname "$rel")"
    mv -n "$f" "$DEST/$rel"
done < <(find "$STAGE" -type f ! -name LAST_OK -print0)
# LAST_OK 는 그것이 가리키는 덤프가 PC 에 있을 때만 바꾼다. 서버가 그 덤프를 정리해 이번에 못 받았으면 PC 의
# 기존 기준(받아 둔 성공 덤프)을 지키고, 아래 신선도 검사가 오래됨으로 알린다
if [[ -f $STAGE/LAST_OK ]]; then
    new_ok=$(sed -n 's/^dump=//p' "$STAGE/LAST_OK")
    if [[ $new_ok =~ ^beez-[0-9]{8}T[0-9]{6}Z\.dump$ && -f $DEST/db/$new_ok ]]; then
        mv -f "$STAGE/LAST_OK" "$DEST/LAST_OK"
    else
        log "경고: 서버 LAST_OK($new_ok)의 덤프를 받지 못해 PC 의 LAST_OK 를 그대로 둔다"
    fi
fi
rm -rf "${STAGE:?}"
after=$(find "$DEST/db" "$DEST/objects" -type f | wc -l)
log "새 파일 $((after - before))개, PC 보관 덤프 $(find "$DEST/db" -name 'beez-*.dump' | wc -l)개·원본 $(find "$DEST/objects" -type f | wc -l)개"

# 서버 백업이 매일 성공하고 있는지: 마지막 성공 덤프 시각(파일 이름의 UTC)으로 본다
ok_dump=$(sed -n 's/^dump=//p' "$DEST/LAST_OK" 2> /dev/null || true)
if [[ ! $ok_dump =~ ^beez-[0-9]{8}T[0-9]{6}Z\.dump$ ]] || [[ ! -f $DEST/db/$ok_dump ]]; then
    log "경고: 끝까지 성공한 서버 백업이 없음(LAST_OK 없음·형식 다름·덤프 없음) — 서버 backup.log 를 본다 (docs/infra/operations.md 8. 백업·복원)"
    exit 1
fi
stamp=${ok_dump#beez-}; stamp=${stamp%.dump}
ok_epoch=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s)
age_hours=$(( ($(date -u +%s) - ok_epoch) / 3600 ))
log "마지막 성공 백업: $ok_dump (${age_hours}시간 전)"
if (( age_hours > STALE_HOURS )); then
    log "경고: 마지막 성공 백업이 ${STALE_HOURS}시간보다 오래됨 — 서버 backup.log 를 본다 (docs/infra/operations.md 8. 백업·복원)"
    exit 1
fi

if (( CHECK )); then
    "$(dirname "$0")/restore-check.sh" "$DEST" "$DEST/db/$ok_dump"
fi
