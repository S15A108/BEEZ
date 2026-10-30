#!/usr/bin/env bash
# BEEZ 배포 스크립트 (S15P31A108-123).
#
# 한 커밋 SHA 의 이미지 셋(backend·ai·frontend-dist)을 이 서버에 올린다.
# Jenkins 배포 잡은 저장소를 그 SHA 로 체크아웃한 뒤 이 파일을 부르기만 한다. 사람도 같은 명령으로 돌린다.
# 배포 로직을 여기 두어 Jenkins 를 다른 도구로 바꿔도 이 파일은 그대로 쓴다.
#
# 사용: infra/deploy/deploy.sh <git SHA 40자> <환경>
#   예) infra/deploy/deploy.sh "$(git rev-parse HEAD)" dev
#
# 환경 값(비밀 포함)은 서버의 /opt/beez/.env.<환경> 에서 읽는다. 저장소에는 없다.
# 환경 중립: 환경 차이는 env 파일로만 준다. 이 파일에 dev/prod 분기를 넣지 않는다.
#
# 순서: 프론트 dist 꺼내기 → backend·ai 이미지 받기 → 컨테이너 교체 + 헬스체크 대기 → 프론트 전환.
# 헬스체크가 실패하면 프론트를 바꾸지 않고 실패로 끝낸다. 되돌리기는 직전 SHA 로 이 스크립트를 다시 돌린다
# (직전 SHA 는 시작할 때 로그에 찍는다).
set -euo pipefail

SHA=${1:?"사용: deploy.sh <git SHA 40자> <환경>"}
ENV_NAME=${2:?"사용: deploy.sh <git SHA 40자> <환경>"}

# 짧은 SHA·브랜치 이름을 받지 않는다. 이미지 태그와 릴리스 폴더 이름이 항상 같은 40자여야 한다
[[ $SHA =~ ^[0-9a-f]{40}$ ]] || { echo "git SHA 40자(소문자 16진수)가 아님: $SHA" >&2; exit 2; }
[[ $ENV_NAME =~ ^[a-z]+$ ]] || { echo "환경 이름이 잘못됨: $ENV_NAME" >&2; exit 2; }

REGISTRY=ghcr.io/s15a108
ENV_FILE=/opt/beez/.env.$ENV_NAME
WEB_ROOT=/var/www/beez
KEEP_RELEASES=5                    # 프론트 릴리스 폴더를 몇 개 남길지. 지워진 SHA 는 이미지에서 다시 꺼낸다
COMPOSE_FILE=$(cd "$(dirname "$0")/.." && pwd)/compose.yaml   # 같은 SHA 의 infra/compose.yaml

[[ -r $ENV_FILE ]] || { echo "env 파일을 읽을 수 없음: $ENV_FILE" >&2; exit 2; }

# 이 스크립트가 만드는 파일을 그룹 쓰기로 만든다. jenkins·ubuntu 가 beez-deploy 그룹으로 배포 폴더를 같이 쓰는데,
# sudo -u jenkins 의 기본 umask(022)면 한쪽이 만든 잠금 파일을 다른 쪽이 열지 못한다
umask 0002

# 한 번에 하나만 배포한다(손 배포와 Jenkins 배포가 겹치지 않게). 대기 시간 근거는 docs/infra/cd.md "배포 스크립트"
exec 9> "$WEB_ROOT/.deploy.lock"
flock -w 300 9 || { echo "다른 배포가 5분 넘게 진행 중이라 시작하지 않음. 끝난 뒤 다시 실행한다" >&2; exit 3; }

# compose.yaml 의 image: ${BACKEND_IMAGE}·${AI_IMAGE} 에 들어간다
export BACKEND_IMAGE=$REGISTRY/beez-backend:$SHA
export AI_IMAGE=$REGISTRY/beez-ai:$SHA
FRONTEND_IMAGE=$REGISTRY/beez-frontend-dist:$SHA

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }
log() { echo "[deploy $(date +%H:%M:%S)] $*"; }

# 중간에 실패해도 꺼내기용 컨테이너와 임시 폴더가 남지 않게 한다
cid=
tmp=
cleanup() {
    [[ -n $cid ]] && docker rm -f "$cid" > /dev/null 2>&1 || true
    [[ -n $tmp ]] && rm -rf "$tmp" || true
}
trap cleanup EXIT
# Jenkins 의 중단·시간 초과는 SIGTERM 으로 끝낸다. 신호로 끝나면 EXIT 처리가 돌지 않으므로 exit 로 바꿔 EXIT 처리를 태운다
trap 'exit 130' INT
trap 'exit 143' TERM

# 컨테이너가 없을 때 docker inspect 가 빈 줄을 찍고 실패하므로, id 가 있을 때만 묻는다
previous=없음
backend_cid=$(compose ps -q backend 2> /dev/null || true)
[[ -n $backend_cid ]] && previous=$(docker inspect -f '{{.Config.Image}}' "$backend_cid")
log "배포 SHA $SHA (환경 $ENV_NAME)"
log "직전 backend 이미지: $previous"
log "직전 프론트: $(readlink "$WEB_ROOT/current" 2> /dev/null || echo 없음)"

# 1) 프론트 dist 꺼내기. 아직 전환하지 않는다
release=$WEB_ROOT/releases/$SHA
if [[ -d $release ]]; then
    log "프론트 릴리스가 이미 있어 꺼내기를 건너뜀: $release"
else
    log "프론트 dist 꺼내기: $FRONTEND_IMAGE"
    docker pull -q "$FRONTEND_IMAGE" > /dev/null
    # scratch 이미지라 실행 명령이 없어 create 가 거부한다. 실행하지 않으므로 아무 명령(noop)이나 준다
    cid=$(docker create "$FRONTEND_IMAGE" noop)
    tmp=$WEB_ROOT/releases/.tmp-$SHA
    rm -rf "$tmp"
    docker cp -q "$cid:/dist" "$tmp"
    # docker cp 는 파일 주인을 실행한 사용자(jenkins:jenkins)로, 권한은 이미지 안의 값(644/755)으로 만든다.
    # 배포 폴더의 그룹(beez-deploy)·그룹 쓰기로 맞춰 ubuntu 도 손으로 지울 수 있게 한다
    chgrp -R "$(stat -c %G "$WEB_ROOT/releases")" "$tmp"
    chmod -R g+w "$tmp"
    # 다 꺼낸 뒤에 제 이름으로 바꾼다. 반쯤 꺼낸 폴더가 릴리스로 남지 않는다
    mv -T "$tmp" "$release"
    tmp=
    docker rm "$cid" > /dev/null
    cid=
fi

# 2) backend·ai 이미지 받기. digest 로 고정한 postgres·seaweedfs 는 매번 묻지 않는다(Docker Hub 장애·요청 제한에 배포가 묶이지 않게).
#    서버에 아직 없으면 아래 up 이 받는다
log "이미지 받기"
compose pull --quiet backend ai

# 3) 바뀐 컨테이너만 교체하고, healthcheck 가 있는 서비스(postgres·ai·backend)가 healthy 가 될 때까지 기다린다.
#    backend 의 healthcheck 는 /actuator/health 다 (compose.yaml). 첫 기동은 Flyway 마이그레이션 때문에 오래 걸린다
log "컨테이너 교체 + 헬스체크 대기 (최대 240초)"
if ! compose up -d --no-build --remove-orphans --wait --wait-timeout 240; then
    log "헬스체크 실패. 프론트는 바꾸지 않았다. 되돌리려면 직전 SHA 로 다시 배포한다"
    compose ps
    compose logs --tail 80 backend ai
    exit 1
fi
compose ps --format '{{.Service}}\t{{.Image}}\t{{.Status}}'

# 4) 프론트 전환. 새 링크를 만든 뒤 이름을 바꿔(mv -T) 한 번에 바꾼다. Nginx 는 reload 할 필요가 없다
ln -sfn "$release" "$WEB_ROOT/current.tmp"
mv -T "$WEB_ROOT/current.tmp" "$WEB_ROOT/current"
log "프론트 전환: $(readlink "$WEB_ROOT/current")"

# 5) 정리. 실패해도 배포는 성공으로 둔다
#    릴리스 이름은 SHA(16진수)라 ls 로 최신순 정렬해도 안전하다
current=$(readlink -f "$WEB_ROOT/current")
# shellcheck disable=SC2012
ls -1dt "$WEB_ROOT"/releases/*/ | tail -n +$((KEEP_RELEASES + 1)) | while read -r dir; do
    dir=${dir%/}
    [[ $dir == "$current" ]] && continue
    if rm -rf "$dir"; then
        log "오래된 릴리스 삭제: $dir"
    else
        log "지우지 못함(권한 확인): $dir"
    fi
done
# 컨테이너가 쓰지 않는 이 저장소 이미지 중 7일 넘은 것만 지운다. 필요하면 GHCR 에서 다시 받는다
docker image prune -af --filter "until=168h" \
    --filter "label=org.opencontainers.image.source=https://github.com/S15A108/BEEZ" > /dev/null || true

log "완료: $SHA"
