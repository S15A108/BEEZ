#!/usr/bin/env bash
# BEEZ 롤백 전 마이그레이션 대조 (S15P31A108-328, 265 후속). 로컬 PC(Git Bash, python 필요)에서 돈다.
#
# 사용: check-rollback-migrations.sh <되돌릴 SHA>   DB Flyway 이력 ↔ 되돌릴 SHA 의 마이그레이션 파일을 번호마다 대조
#       check-rollback-migrations.sh find <번호>     "판단 대상" 번호의 dev 판과 저장소 이력의 모든 판 checksum
#       check-rollback-migrations.sh checksum <파일>… 파일의 Flyway checksum (손 배포한 파일 맞춰 보기)
# 대조 결과: 문제가 없으면 "대조 끝 (…)" 한 줄만 찍고 exit 0, 번호별 줄이 있으면 exit 1, 대조하지 못하면 "… — 중단" 과 exit 2.
#
# DB 이력은 ssh beez 로 조회만 한다(psql select). 저장소는 이 스크립트가 든 git 저장소를 쓴다.
# 결과 줄의 뜻과 판단 기준: docs/infra/operations.md §4 2단계
DIR=backend/src/main/resources/db/migration
JDIR=backend/src/main/java/db/migration
stop() { echo "$1 — 중단"; exit 2; }
usage="사용: check-rollback-migrations.sh <되돌릴 SHA> | find <번호> | checksum <파일>…"

# Flyway checksum = BOM 과 줄바꿈 문자(\r·\n)를 뺀 UTF-8 내용의 CRC32 를 부호 있는 32비트로 (2026-11-06 서버 V10·V13·V37 과 일치 확인)
sum='import sys,zlib; t=sys.stdin.buffer.read().decode("utf-8").lstrip(chr(0xfeff)); c=zlib.crc32(t.replace("\r","").replace("\n","").encode()); print(c-(1<<32) if c>=1<<31 else c)'
# Windows 의 python3 는 스토어 안내 껍데기일 수 있어, 찾기만 하지 않고 실제로 돌려 본다
PY=
for c in python3 python; do
    if "$c" -c "$sum" < /dev/null > /dev/null 2>&1; then PY=$c; break; fi
done
[ -n "$PY" ] || stop "python 을 실행하지 못함"
flyway_sum() { "$PY" -c "$sum"; }

case $1 in
    checksum)
        shift
        [ $# -gt 0 ] || { echo "$usage"; exit 2; }
        rc=0
        for f in "$@"; do
            if [ -f "$f" ] && [ -r "$f" ]; then echo "$(flyway_sum < "$f") $f"; else echo "읽지 못함: $f"; rc=2; fi
        done
        exit $rc ;;
esac

REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2> /dev/null) || stop "이 스크립트가 git 저장소 안에 없음"
# quotePath 를 끄지 않으면 ls-tree 가 한글 파일 이름을 "V1__\355…" 처럼 따옴표·8진수로 찍어 git show 가 그 경로를 못 찾는다
g() { git -c core.quotePath=false -C "$REPO" "$@"; }
T=$(mktemp -d) || stop "임시 폴더를 만들지 못함"
trap 'rm -rf "$T"' EXIT
# <커밋>:<경로> 의 checksum. 파일을 읽지 못하면 빈 입력의 checksum(0)을 찍지 않고 실패를 돌려준다
blob_sum() { g cat-file blob "$1" > "$T/blob" 2> /dev/null && flyway_sum < "$T/blob"; }

if [ "$1" = find ]; then
    [ $# -eq 2 ] || { echo "$usage"; exit 2; }
    V=${2#[Vv]}; F=${V//./_}   # 대조 결과의 번호 그대로(V40 처럼 V 가 붙어도 됨). 점 버전 1.1 은 파일 이름 V1_1__ 로 찾는다
    g fetch -q origin || stop "fetch 실패"
    # 같은 번호 파일이 둘 이상이어도 한 줄에 하나씩 찍는다
    g ls-tree -r --name-only origin/dev -- "$DIR/" | grep "/V${F}__" | while read -r f; do
        echo "dev 판: $(blob_sum "origin/dev:$f" || echo 읽지못함) ${f##*/}"
    done > "$T/found"
    g log --branches --remotes --format=%h -- ":(glob)$DIR/**/V${F}__*" | while read -r c; do
        g ls-tree -r --name-only "$c" -- "$DIR/" | grep "/V${F}__" | while read -r f; do
            echo "$(blob_sum "$c:$f" || echo 읽지못함) $c ${f##*/}"
        done
    done | sort -u -k1,1 >> "$T/found"
    [ -s "$T/found" ] || { echo "V$V 파일 없음 (dev·저장소 이력 모두. 번호를 확인하거나 손 배포한 사람에게 파일을 받음)"; exit 1; }
    cat "$T/found"
    exit 0
fi

[ $# -eq 1 ] && [ -n "$1" ] || { echo "$usage"; exit 2; }
OLD=$1
g fetch -q origin || stop "fetch 실패"
g cat-file -e "$OLD^{commit}" 2> /dev/null || stop "없는 SHA: $OLD"
# installed_rank 순서가 중요하다. 같은 번호의 나중 행(repair 의 DELETE, 다시 적용한 행)이 앞의 행을 덮는다
ssh beez "docker compose -p beez exec -T postgres sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -AtF \"|\" -c \"select version, checksum, success, type, description from flyway_schema_history where version is not null order by installed_rank\"'" > "$T/db" ||
    stop "DB 이력을 받지 못함"
[ -s "$T/db" ] || stop "DB 이력을 받지 못함 (빈 응답)"

g ls-tree -r --name-only "$OLD" -- "$DIR/" "$JDIR/" | grep -E '/V[0-9][0-9_]*__' > "$T/files"
while read -r f; do
    v=${f##*/V}; v=${v%%__*}; v=${v//_/.}   # V1_1__ 은 DB 에 1.1 로 기록된다
    s=${f#*__}; s=${s%.*}; s=${s//_/ }       # 설명 = 파일 이름의 __ 뒤에서 확장자를 빼고 _ 를 공백으로
    case $f in
        *.sql) s_sum=$(blob_sum "$OLD:$f") || stop "되돌릴 SHA 의 파일을 읽지 못함: $f" >&2   # 표준 출력은 $T/old 로 가므로 오류로 보인다
               echo "$v|$s_sum|SQL|$s" ;;
        *) echo "$v||JDBC|$s" ;;   # Java 마이그레이션은 checksum 이 없다(DB 에도 빈 값)
    esac
done < "$T/files" > "$T/old"

# Flyway 기본 검증(outOfOrder 꺼짐, *:future 만 무시)으로 예전 backend 가 어떻게 되는지 번호마다 가른다.
# 번호의 높낮이는 수로 비교하므로 정수 번호(V1, V2 …)만 맞다. 점 버전(V1_10 과 V1_9)은 순서가 틀릴 수 있다
awk -F'|' -v nfile="$T/n" '
    # 설명은 마지막 칸이라 "|" 가 들어 있어도 끝까지 한 칸으로 읽는다
    function rest(k,   r, i) { r = $0; for (i = 1; i < k; i++) sub(/^[^|]*[|]/, "", r); return r }
    NR == FNR { o[$1] = $2; ot[$1] = $3; od[$1] = rest(4); if ($1 + 0 > om) om = $1 + 0; next }
    $4 == "DELETE" { delete a[$1]; next }    # flyway repair 가 지움 처리한 번호. 앞서 적용된 행을 없던 것으로 본다
    $4 == "BASELINE" { if ($1 + 0 > bl) bl = $1 + 0; next }   # 이 번호 이하는 Flyway 가 적용된 것으로 보고 검사하지 않는다
    $4 != "SQL" && $4 != "JDBC" { next }     # 스키마 생성 같은 Flyway 기록용 행은 마이그레이션이 아니다
    { a[$1] = 1; c[$1] = $2; ok[$1] = $3; t[$1] = $4; ds[$1] = rest(5) }
    END {
        for (v in a) { n++; if (v + 0 > dm) dm = v + 0 }
        print n + 0 > nfile
        for (v in a) {
            if (ok[v] != "t") { print "V" v ": 실패한 마이그레이션 — 어느 backend도 기동 못 함. 되돌리기 전에 스키마 상태부터 확인"; continue }
            if (!(v in o) && v + 0 > om) { print "V" v ": 되돌릴 SHA가 모르는 상위 번호 — 판단 대상 (DB checksum " c[v] ")"; continue }
            if (!(v in o)) { print "V" v ": DB에만 있는데 되돌릴 SHA의 최신 번호보다 낮음 — 되돌리면 예전 backend 기동 실패"; continue }
            m = ""
            if (o[v] != c[v]) m = m " checksum(DB " c[v] ", 되돌릴 SHA " o[v] ")"
            if (ot[v] != t[v]) m = m " 유형(DB " t[v] ", 되돌릴 SHA " ot[v] ")"
            if (od[v] != ds[v]) m = m " 설명(DB \"" ds[v] "\", 되돌릴 SHA \"" od[v] "\")"
            if (m != "") print "V" v ": 기록 다름 —" m " — 되돌리면 예전 backend 기동 실패"
        }
        for (v in o) if (!(v in a) && v + 0 > bl) print "V" v ": 되돌릴 SHA에만 있고 DB에 없음 — " \
            (v + 0 < dm ? "되돌리면 예전 backend 기동 실패" : "되돌리는 순간 이 마이그레이션이 실행됨. 판단 대상과 같이 읽음")
    }' "$T/old" "$T/db" | sort -n -k1.2 > "$T/out"
# awk 가 깨지면 줄 없이 "대조 끝" 이 나와 되돌려도 되는 것처럼 보인다. 파이프 끝(sort)이 아니라 awk 의 결과를 본다
[ "${PIPESTATUS[0]}" -eq 0 ] && [ -s "$T/n" ] || stop "대조 계산(awk) 실패"

cat "$T/out"
echo "대조 끝 (DB $(cat "$T/n")개, 되돌릴 SHA $(wc -l < "$T/old" | tr -d ' ')개)"
[ -s "$T/out" ] && exit 1
exit 0
