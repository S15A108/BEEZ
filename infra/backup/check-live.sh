#!/usr/bin/env bash
# 운영 DB 와 운영 SeaweedFS 원본 대조 (S15P31A108-211). 읽기만 한다.
#
# document_version 마다 storage_key 로 원본을 내려받아 SHA-256 이 content_hash 와 같은지 본다.
# 쓰는 때: 배포·재배포 뒤 원본 보존 확인, 복원 뒤 확인(docs/infra/operations.md 8. 백업·복원).
# health 200 은 원본이 있는지 알려 주지 않는다 — SeaweedFS 가 비어 있어도 backend 는 뜬다.
#
# 사용(서버): infra/backup/check-live.sh <환경>
#   예) infra/backup/check-live.sh dev
#
# 키는 env 파일의 읽기 전용 백업 키(SEAWEEDFS_BACKUP_*)를 쓴다. 원본을 바꾸거나 지울 수 없다.
# 종료 코드: 0 = 모두 일치(0건 포함), 1 = 없거나 다른 원본이 있음, 2 = 사용법·환경 오류·DB 조회 실패
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 에서 시험할 때 컨테이너 경로가 바뀌지 않게. 리눅스에서는 영향 없음

ENV_NAME=${1:?"사용: check-live.sh <환경>"}
[[ $ENV_NAME =~ ^[a-z]+$ ]] || { echo "환경 이름이 잘못됨: $ENV_NAME" >&2; exit 2; }
ENV_FILE=${ENV_FILE:-/opt/beez/.env.$ENV_NAME}
PROJECT=${COMPOSE_PROJECT:-beez}
CURL_IMAGE=curlimages/curl:8.10.1@sha256:d9b4541e214bcd85196d6e92e2753ac6d0ea699f0af5741f8c6cccbfcf00ef4b

[[ -r $ENV_FILE ]] || { echo "env 파일을 읽을 수 없음: $ENV_FILE" >&2; exit 2; }
# env 파일을 source 하지 않는다(값이 셸 코드로 실행되지 않게). 필요한 줄만 꺼낸다
# env 파일은 배포(compose)와 같은 규칙으로 읽는다(따옴표·인라인 주석·CRLF). check-port.sh 의 envs() 와 같은 방식.
# compose 오류 문구에는 값이 섞일 수 있어 원문을 내보내지 않는다
ENV_ALL=$(printf 'services: {}\n' | docker compose -f - --env-file "$ENV_FILE" config --environment 2> /dev/null) ||
    { echo "env 파일을 compose 로 해석하지 못함(형식 오류일 수 있어 문구는 숨김). 서버에서 docker compose 로 직접 확인한다" >&2; exit 2; }
env_value() { printf '%s\n' "$ENV_ALL" | sed -n "s/^$1=//p" | tail -n 1; }
BUCKET=$(env_value DOCUMENT_S3_BUCKET); BUCKET=${BUCKET:-beez-documents}
AK=$(env_value SEAWEEDFS_BACKUP_ACCESS_KEY); SK=$(env_value SEAWEEDFS_BACKUP_SECRET_KEY)
[[ -n $AK && -n $SK ]] || { echo "env 파일에 SEAWEEDFS_BACKUP_* 가 없음" >&2; exit 2; }

pg=$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter label=com.docker.compose.service=postgres)
[[ -n $pg ]] || { echo "postgres 컨테이너가 없음 (project=$PROJECT)" >&2; exit 2; }

log() { echo "[check-live $(date +%H:%M:%S)] $*"; }
log "문서 버전 원본 대조 ($PROJECT, bucket $BUCKET)"
# 키는 -e 이름만 넘겨 명령줄(프로세스 목록)에 값이 보이지 않게 한다
export AK SK
# DB 조회를 먼저 끝내고 결과를 넘긴다. 파이프로 바로 이으면 조회 실패도 아래 "원본이 DB 와 맞지 않는다"로 보인다
rows=$(docker exec "$pg" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -XAt -F " " -v ON_ERROR_STOP=1 -c "SELECT id, storage_key, content_hash FROM document_version ORDER BY id"') ||
    { log "실패: 운영 DB 조회 실패 — 원본 대조를 하지 못함(postgres 상태부터 본다)"; exit 2; }
# 0건이면 빈 줄 하나도 넘기지 않는다(빈 줄은 key 없는 "없음" 한 건으로 세어진다)
{ [[ -n $rows ]] && printf '%s\n' "$rows"; true; } \
  | docker run -i --rm --network "${PROJECT}_storage" --entrypoint sh -e AK -e SK -e EP="http://seaweedfs:8333/$BUCKET" \
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
      [ "$bad" -eq 0 ]' || { log "실패: 운영 원본이 DB 와 맞지 않는다"; exit 1; }
log "통과"
