#!/usr/bin/env bash
# 서버 백업을 PC 로 당겨 온다 — 서버 밖 보관 (S15P31A108-211).
#
# 사용(PC Git Bash): infra/backup/pull-backup.sh [받을 폴더] [--check]
#   예) infra/backup/pull-backup.sh                       # ~/beez-backup/dev 로 받기
#       infra/backup/pull-backup.sh ~/beez-backup/dev --check   # 받은 뒤 최근 덤프로 복원 시험(restore-check.sh, Docker 필요)
#
# 서버의 backup.sh 가 만든 BACKUP_ROOT(기본 /var/backups/beez)에서 PC 에 아직 없는 파일만 받는다.
#   - 지우지 않는다: 서버에서 사라진 덤프(14개 보존)·원본도 PC 에는 남는다
#   - 덮어쓰지 않는다: 이미 받은 파일은 다시 받지 않는다(tar --skip-old-files). 원본 key 는 UUID 라 내용이 바뀌지 않는다
#   - 서버 백업이 도는 중이면 끝날 때까지 기다렸다 받는다(같은 잠금 파일). 덤프만 받고 그 원본을 못 받는 일이 없게
#
# 종료 코드: 0 = 받음(새 파일 0개 포함), 1 = 최근 덤프가 36시간보다 오래됨(서버 백업이 멈췄을 수 있음),
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

log() { echo "[pull-backup $(date +%H:%M:%S)] $*"; }
mkdir -p "$DEST/db" "$DEST/objects"

# 서버에서 도는 부분. PC 가 가진 파일 목록을 표준입력으로 받아, 없는 것만 tar 로 내보낸다.
# rclone 이 쓰는 중인 *.partial 과 덤프 임시 파일(*.dump.tmp)은 보내지 않는다
# shellcheck disable=SC2016  # 서버에서 펼칠 변수다
remote_script='
set -euo pipefail
cd "$1"
exec 9< .backup.lock || { echo "백업 잠금 파일이 없음: $1/.backup.lock (서버에서 backup.sh 를 한 번도 안 돌렸나?)" >&2; exit 4; }
flock -s -w 600 9 || { echo "10분 기다려도 서버 백업이 끝나지 않음" >&2; exit 5; }
new=$(LC_ALL=C comm -23 \
    <({ find db -type f -name "beez-*.dump"; find objects -type f ! -name "*.partial"; } | LC_ALL=C sort) \
    <(LC_ALL=C sort))
# 새 파일이 없어도 빈 tar 를 보낸다(PC 의 tar 가 빈 입력을 오류로 본다)
if [ -z "$new" ]; then tar -cf - -T /dev/null; exit 0; fi
printf "%s\n" "$new" | tar -cf - --verbatim-files-from -T -
'

before=$(find "$DEST/db" "$DEST/objects" -type f | wc -l)
log "받기: $HOST:$REMOTE → $DEST"
# shellcheck disable=SC2029  # 서버 스크립트와 경로를 PC 에서 %q 로 감싸 넘긴다(의도)
(cd "$DEST" && find db objects -type f ! -name '*.partial' | LC_ALL=C sort) \
  | ssh "$HOST" "bash -c $(printf '%q' "$remote_script") _ $(printf '%q' "$REMOTE")" \
  | tar -C "$DEST" -xf - --skip-old-files \
  || { echo "받기 실패 (위 메시지 확인)" >&2; exit 2; }
after=$(find "$DEST/db" "$DEST/objects" -type f | wc -l)
log "새 파일 $((after - before))개, PC 보관 덤프 $(find "$DEST/db" -name 'beez-*.dump' | wc -l)개·원본 $(find "$DEST/objects" -type f | wc -l)개"

# 서버 백업이 매일 돌고 있는지: 가장 최근 덤프 시각(파일 이름의 UTC)으로 본다
# shellcheck disable=SC2012  # 이름이 beez-<UTC 시각>.dump 뿐이라 정렬로 충분하다
latest=$(ls -1 "$DEST"/db/beez-*.dump 2> /dev/null | sort | tail -n 1)
[[ -n $latest ]] || { echo "받은 덤프가 없음" >&2; exit 1; }
stamp=$(basename "$latest" .dump); stamp=${stamp#beez-}
latest_epoch=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s)
age_hours=$(( ($(date -u +%s) - latest_epoch) / 3600 ))
log "최근 덤프: $(basename "$latest") (${age_hours}시간 전)"
if (( age_hours > STALE_HOURS )); then
    log "경고: 최근 덤프가 ${STALE_HOURS}시간보다 오래됨 — 서버 백업 로그를 본다 (docs/infra/operations.md 8. 백업·복원)"
    exit 1
fi

if (( CHECK )); then
    "$(dirname "$0")/restore-check.sh" "$DEST" "$latest"
fi
