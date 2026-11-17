#!/usr/bin/env bash
# 서버에서 받은 원본 덤프를 비식별화해 제출용 덤프를 만든다 (S15P31A108-461).
# 원본은 임시 컨테이너 안에서만 다루고, 결과물만 저장소의 exec/beez-db-dump.sql.gz 로 남긴다.
# 문서 검색용 임베딩 때문에 SQL 이 80MB 가 넘어 gzip 으로 압축한다(약 23MB).
#
#   DEMO_PASSWORD='시연용 비밀번호' KEEP_NAMES='전진,백엔드진' exec/dump/make-public-dump.sh ~/beez-dump.sql
#
# - 저장소 루트에서 Git Bash 로 실행한다. Docker Desktop 이 켜져 있어야 한다.
# - 결과 덤프에 example.com·example.test·beez.ai.kr 이 아닌 이메일 주소가 남아 있으면 실패하고 결과 파일을 지운다.
set -euo pipefail

raw=${1:?원본 덤프 경로를 첫 인자로 주세요 (예: ~/beez-dump.sql)}
out=${2:-exec/beez-db-dump.sql.gz}
sql=${out%.gz}
: "${DEMO_PASSWORD:?DEMO_PASSWORD 에 시연용 비밀번호를 넣어 주세요}"
keep_names=${KEEP_NAMES:-}
image=pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d
name=beez-sanitize-$$

[ -r "$raw" ] || { echo "원본 덤프를 읽을 수 없습니다: $raw" >&2; exit 1; }
[ -f infra/compose.yaml ] || { echo "저장소 루트에서 실행하세요" >&2; exit 1; }

# 이미지가 데이터 폴더를 볼륨으로 선언해 docker run 이 익명 볼륨을 만든다. -v 로 함께 지워야 원본 DB 가 PC 에 남지 않는다
cleanup() { docker rm -fv "$name" > /dev/null 2>&1 || true; }
trap cleanup EXIT

echo "1/4 임시 DB 띄우기"
docker run -d --name "$name" -e POSTGRES_USER=beez -e POSTGRES_PASSWORD=sanitize -e POSTGRES_DB=beez "$image" > /dev/null
for _ in $(seq 1 30); do
  docker exec "$name" pg_isready -U beez -d beez > /dev/null 2>&1 && break
  sleep 1
done
sleep 2

echo "2/4 원본 덤프 넣기"
docker exec -i "$name" psql -q -v ON_ERROR_STOP=1 -U beez -d beez < "$raw" > /dev/null

echo "3/4 비식별화"
docker exec -i "$name" psql -q -U beez -d beez \
  -v demo_password="$DEMO_PASSWORD" -v keep_names="$keep_names" < exec/dump/sanitize.sql

echo "4/4 제출용 덤프 만들기: $out"
docker exec "$name" pg_dump -U beez -d beez --no-owner --no-privileges > "$sql"

# 남은 개인 이메일이 없는지 확인한다. example.com·example.test(예시 주소) 와 코드·문서에 원래 있는 주소(no-reply@beez.ai.kr 등)는 허용한다
leaks=$(grep -oE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' "$sql" \
  | grep -viE '@example\.(com|test)$|@beez\.ai\.kr$' | sort -u || true)
if [ -n "$leaks" ]; then
  rm -f "$sql"
  echo "비식별화 뒤에도 이메일 주소가 남아 결과 파일을 지웠습니다. 아래 주소가 어느 테이블에 있는지 확인해 sanitize.sql 에 더하세요:" >&2
  printf '%s\n' "$leaks" | sed 's/^/  /' >&2
  exit 1
fi

if [ "$sql" != "$out" ]; then
  gzip -9 -c "$sql" > "$out" && rm -f "$sql"
fi

echo
echo "완료: $out ($(wc -c < "$out") bytes)"
echo "로그인 계정 (비밀번호는 모두 DEMO_PASSWORD):"
docker exec "$name" psql -U beez -d beez -At -F ' | ' \
  -c "select email, display_name from account where display_name not like '사용자%' order by id"
