#!/usr/bin/env bash
# BEEZ 배포 점검 (S15P31A108-245). 서버에서 돈다. deploy.sh 와 손 점검(docs/infra/env.md "배포 전에 점검하기")이 같이 쓴다.
#
# 사용: check-port.sh port  <env 파일> <compose 파일>   컨테이너 교체 전. env 필수값 + backend 포트·Nginx upstream 일치
#       check-port.sh nginx <env 파일> <compose 파일>   교체 뒤. 실행 중인 Nginx 가 지금 떠 있는 backend 로 보내는지
# 성공이면 port: "env ok"·"port ok (<포트>)", nginx: "nginx ok (<started_at>)" 을 찍고 exit 0. 실패는 모두 exit 1.
#
# 비밀값을 찍지 않는다. compose 오류 원문은 한 줄도 내보내지 않고 고정 문구로 바꾼다(아래 safe).
# Nginx 사이트 설정은 PUBLIC_ORIGIN 의 호스트 이름으로 찾는다(<호스트>.conf). 서버 하나에 환경 하나가 전제다.
# 근거·문구 뜻: docs/infra/env.md "배포 전에 점검하기"·"Nginx 반영 확인"
mode=$1
envf=$2
fail() { echo "$1"; exit 1; }
case $mode in port | nginx) ;; *) fail "사용: check-port.sh port|nginx <env 파일> <compose 파일>" ;; esac
[ $# -eq 3 ] || fail "사용: check-port.sh port|nginx <env 파일> <compose 파일>"
sites=${NGINX_SITES_DIR:-/etc/nginx/sites-enabled}   # 시험에서만 바꾼다

cf=$(cat "$3" 2> /dev/null) && [ -n "$cf" ] || fail "compose 파일을 읽지 못함: $3"
# 오류 문구로 원인을 가리기 전에, 값과 무관한 원인은 먼저 직접 확인한다
command -v docker > /dev/null || fail "서버에 docker 없음"
[ -r "$envf" ] || fail "env 파일 없음·읽기 불가: $envf"
docker compose config --help 2> /dev/null | grep -q -- '--environment' ||
    fail "서버 docker compose 가 없거나 config --environment 를 모름 (버전 확인)"
# compose 오류 문구는 env 값을 그대로 담기도 한다(따옴표가 안 닫힌 값, 포트 형식 오류 등).
# 원문은 한 줄도 내보내지 않고 고정 문구로 바꾼다. 해석하는 것은 오류의 첫 줄(compose 자신의 문구)뿐이다.
# 그 뒤 줄은 여러 줄 값의 나머지일 수 있다. 변수 이름은 compose 파일에 있는 이름일 때만 보여 준다
vars=" $(printf '%s\n' "$cf" | grep -oE '[$][{][A-Za-z_][A-Za-z0-9_]*' | cut -c3- | sort -u | tr '\n' ' ')"
safe() { awk -v vars="$vars" '
  !NF || seen { next }
  { seen = 1 }
  match($0, /required variable [A-Za-z0-9_]+ is missing a value/) {
    v = substr($0, RSTART + 18, RLENGTH - 37)
    if (index(vars, " " v " ")) { print "필수값 " v " 이 없거나 빈 값"; next } }
  /^failed to read / { print "env 파일 형식 오류 — 따옴표·${ 문법 등 (값은 숨김)"; next }
  { print "compose 오류 (값이 섞일 수 있어 문구는 숨김)" }'; }
err=$(printf '%s\n' "$cf" | docker compose -f - --env-file "$envf" config --quiet 2>&1 > /dev/null) ||
    { printf '%s\n' "$err" | safe; fail "env 점검 실패"; }
[ "$mode" = port ] && echo "env ok"
# 값은 compose 가 해석한 것을 쓴다. 주석·따옴표·CRLF·중복 키(마지막 값)를 compose 와 똑같이 읽는다.
# 이 명령은 env 파일의 모든 값을 내보내므로 필요한 값만 꺼내고 찍지 않는다
envs() { printf 'services: {}\n' | docker compose -f - --env-file "$envf" config --environment; }
err=$(envs 2>&1 > /dev/null) || { printf '%s\n' "$err" | safe; fail "env 읽기 실패"; }
all=$(envs 2> /dev/null)
p=$(printf '%s\n' "$all" | sed -n 's/^BACKEND_HOST_PORT=//p')
case $p in '' | *[^0-9]*) fail "port 값 없음·이상 (숫자가 아님, 값은 숨김)" ;; esac
origin=$(printf '%s\n' "$all" | sed -n 's/^PUBLIC_ORIGIN=//p')
host=${origin#*://}
host=${host%%[:/]*}
case $origin in http://?* | https://?*) ;; *) host= ;; esac
case $host in '' | *[^A-Za-z0-9.-]*) fail "PUBLIC_ORIGIN 에서 호스트를 읽지 못함 (값은 숨김)" ;; esac

if [ "$mode" = nginx ]; then
    # 같은 /api/ping 을 Nginx 를 거쳐 한 번, compose backend 컨테이너가 연 포트로 직접 한 번 부른다.
    # started_at(그 backend 프로세스의 기동 시각)이 같으면 Nginx 가 바로 그 backend 로 보내고 있다.
    # Nginx 경유는 이 서버의 Nginx 로 바로 붙는다(--connect-to). 주소·인증서 이름은 PUBLIC_ORIGIN 그대로다
    ts() { sed -n 's/.*"started_at"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'; }
    # 한 번 부르고 "ok <started_at>" 또는 "err <원인>" 을 찍는다. 원인은 curl 종료 코드나 HTTP 상태만 쓴다
    # (curl 오류 문구·응답 본문은 내보내지 않는다). 종료 코드 뜻: 7 연결 거부, 28 시간 초과, 35·60 TLS
    ask() {
        local out rc code started
        out=$(curl -sS --max-time 10 -w '\n%{http_code}' "$@" 2> /dev/null)
        rc=$?
        code=${out##*$'\n'}
        started=$(printf '%s\n' "${out%$'\n'*}" | ts)
        if [ -n "$started" ]; then echo "ok $started"
        elif [ "$rc" -ne 0 ]; then echo "err 응답 없음(curl 종료 코드 $rc)"
        else echo "err JSON 아님(HTTP $code, started_at 없음)"; fi
    }
    out=$(docker ps --filter label=com.docker.compose.project=beez --filter label=com.docker.compose.service=backend \
        --format '{{.Ports}}') || fail "docker 조회 실패: backend 컨테이너 포트 확인 못 함"
    # backend 컨테이너가 연 127.0.0.1 포트는 정확히 하나여야 한다
    # shellcheck disable=SC2046
    set -- $(printf '%s\n' "$out" | sed -n 's/.*127\.0\.0\.1:\([0-9]*\)->.*/\1/p')
    [ $# -eq 1 ] || fail "backend 컨테이너 포트가 $# 개 (1개여야 함)"
    bport=$1
    # 둘 중 하나라도 응답을 못 받았을 때만 다시 묻는다(교체 직후 일시 실패). 둘 다 응답했으면 그 한 쌍으로 판정한다.
    # 시각이 다르면(reload 전 등) 다시 물어도 같으므로 기다리지 않는다
    for try in 1 2 3; do
        via=$(ask --connect-to ::127.0.0.1: "$origin/api/ping")
        direct=$(ask "http://127.0.0.1:$bport/api/ping")
        case "$via|$direct" in ok\ *\|ok\ *) break ;; esac
        [ "$try" -lt 3 ] && sleep "${NGINX_RETRY_WAIT:-2}"   # 대기 시간은 시험에서만 바꾼다
    done
    detail="Nginx 경유=${via#* } / backend 직접=${direct#* }"
    case "$via|$direct" in
        ok\ *\|ok\ *) [ "$via" = "$direct" ] || fail "nginx 다름: $detail" ;;   # 다른 backend 로 보냄. reload 전이면 여기
        *) fail "nginx 확인 실패: $detail (${try}회 시도)" ;;
    esac
    echo "nginx ok (${via#ok })"
    exit 0
fi

blk='/upstream[[:space:]]+beez_backend[[:space:]]*[{]/'
conf=$sites/$host.conf
[ -r "$conf" ] || fail "Nginx 설정 없음: $conf"
# 지원하는 upstream 주소 표기는 127.0.0.1:<포트>·localhost:<포트> 둘이다. 아래 (a)·(c) 가 같은 패턴을 쓴다
hit="(127\.0\.0\.1|localhost):$p([^0-9]|$)"
# (a) Nginx 의 다른 곳이 같은 포트로 보내는가. 꺼져 있는 서비스(예: Jenkins 8080)도 설정으로 잡는다
for f in "$sites"/*; do
    body=$(sed 's/#.*//' "$f") || fail "Nginx 설정 읽기 실패: $f"
    printf '%s\n' "$body" | awk "$blk{s=1} s==0; s&&/[}]/{s=0}" | grep -E "$hit" > /dev/null &&
        fail "port 충돌: Nginx 의 다른 설정이 포트 $p 사용 ($f)"
done
# (b) 지금 그 포트를 연 프로그램이 있으면 떠 있는 backend 여야 한다
listen=$(ss -Hltn "sport = :$p") || fail "ss 실행 실패: 포트 $p 사용 여부 확인 못 함"
if [ -n "$listen" ]; then
    ports=$(docker ps --filter label=com.docker.compose.project=beez --filter label=com.docker.compose.service=backend \
        --format '{{.Ports}}') || fail "docker 조회 실패: 포트 $p 사용 주체 확인 못 함"
    case $ports in *"127.0.0.1:$p->"*) ;; *) fail "port 충돌: 다른 프로그램이 $p 사용 중" ;; esac
fi
# (c) upstream beez_backend 의 server 는 하나여야 하고, 지원하는 주소 표기로 env 와 같은 포트를 가리켜야 한다
up=$(sed 's/#.*//' "$conf" | awk "$blk{f=1} f; f&&/[}]/{exit}")
[ -n "$up" ] || fail "upstream beez_backend 없음: $conf"
servers=$(printf '%s\n' "$up" | grep -oE '(^|[[:space:]{;])server[[:space:]]+[^;]+')
[ "$(printf '%s\n' "$servers" | grep -c .)" -eq 1 ] || fail "upstream beez_backend 의 server 가 하나가 아님: $conf"
printf '%s\n' "$servers" | grep -E "server[[:space:]]+(127\.0\.0\.1|localhost):[0-9]+([^0-9]|$)" > /dev/null ||
    fail "지원하지 않는 upstream 주소 (127.0.0.1:<포트>·localhost:<포트> 만): $conf"
printf '%s\n' "$servers" | grep -E "$hit" > /dev/null || fail "port 다름: env=$p"
echo "port ok ($p)"
