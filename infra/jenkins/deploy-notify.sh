#!/usr/bin/env bash
# 배포 실패 알림 (S15P31A108-74). Jenkins beez-deploy 가 실패·중단·시간 초과로 끝날 때 팀 Mattermost 채널에 보낸다.
#
# 서버 위치: /etc/jenkins/deploy-notify.sh (root 소유 755). 배포할 커밋이 아니라 서버에 둔 파일을 부른다.
#   SHA 검사에서 멈춘 실패도 알려야 하는데, 그때는 배포할 커밋을 아직 꺼내지 않았기 때문이다.
# 웹훅 주소: /etc/jenkins/deploy-notify.curl (root:jenkins 640). curl 설정 형식 한 줄 `url = "https://.../hooks/<id>"`.
#   주소를 명령줄·환경변수에 두지 않아 프로세스 목록과 Jenkins 콘솔 로그에 찍히지 않는다. 저장소에는 없다.
# 넣는 값: 빌드 번호·환경·SHA·실패 단계·빌드 링크만. 콘솔 로그는 넣지 않는다(실패 때 앱 로그가 찍혀 값이 섞일 수 있음).
#
# 알림이 실패해도 배포 결과는 바꾸지 않는다. 항상 exit 0 으로 끝낸다.
# 절차: docs/infra/operations.md "배포 실패 알림"
set -u

CONF=${DEPLOY_NOTIFY_CONF:-/etc/jenkins/deploy-notify.curl}

if [[ ! -r $CONF ]]; then
    echo "알림 설정 파일을 읽을 수 없어 알림을 보내지 않음: $CONF"
    exit 0
fi

# 메시지에 들어가는 값은 정해진 문자만 남긴다. GIT_SHA 는 검사 전 실패면 사람이 넣은 아무 값일 수 있다
clean() { printf %s "$1" | tr -cd 'A-Za-z0-9._:/#-' | cut -c1-120; }
sha=$(clean "${GIT_SHA:-}")
[[ $sha =~ ^[0-9a-f]{40}$ ]] && sha=${sha:0:7} || sha="(SHA 형식 오류)"
# 비었거나 걸러서 비게 된 값은 ? 로 둔다(? 는 clean 이 지우는 문자라 걸러 낸 뒤에 채운다)
build=$(clean "${BUILD_NUMBER:-}"); build=${build:-?}
env_name=$(clean "${DEPLOY_ENV:-}"); env_name=${env_name:-?}
stage=$(clean "${FAILED_STAGE:-}"); stage=${stage:-?}
url=$(clean "${BUILD_URL:-}")

text=":rotating_light: **beez-deploy #$build 실패** ($env_name, \`$sha\`, 단계 $stage)"
[[ -n $url ]] && text="$text · [콘솔 출력](${url}console)"
# 받은 사람이 바로 열 수 있게 GitHub 의 운영 안내 6절로 링크한다(앵커는 "6-배포-실패-알림" 을 퍼센트 인코딩한 값)
text="$text · [복구 안내](https://github.com/S15A108/BEEZ/blob/dev/docs/infra/operations.md#6-%EB%B0%B0%ED%8F%AC-%EC%8B%A4%ED%8C%A8-%EC%95%8C%EB%A6%BC)"

# 위에서 따옴표·역슬래시를 걸러 냈으므로 JSON 에 그대로 넣어도 된다.
# curl 의 오류 문구는 버린다(설정 파일 문제 등에서 주소가 섞일 수 있음). 대신 종료 코드를 남겨 원인을 좁힌다
rc=0
code=$(printf '{"text":"%s"}' "$text" |
    curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -K "$CONF" \
        -H 'Content-Type: application/json' --data-binary @- 2> /dev/null) || rc=$?
if [[ $rc -eq 0 && $code == 200 ]]; then
    echo "배포 실패 알림 보냄"
else
    echo "배포 실패 알림을 보내지 못함(HTTP ${code:-000}, curl 종료 코드 $rc). 배포 결과와는 무관"
fi
exit 0
