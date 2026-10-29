#!/usr/bin/env bash
# github-actions 계정의 Jenkins API 토큰을 새로 만들어 표준 출력으로만 내보낸다 (S15P31A108-123).
#
# 서버에서 root 로 돌린다. 로컬 PC(Git Bash)에서 GitHub 시크릿으로 바로 넘기는 사용법은 docs/infra/cd.md
# "Actions → Jenkins 호출" 절에 있다. 토큰은 화면·파일·셸 기록에 남지 않는다.
#
# 새 토큰을 만들어도 옛 토큰은 그대로 살아 있다. 바꾼 뒤에는 Jenkins 화면
# (beez-admin 로그인 → 사용자 github-actions → Security → API Token)에서 옛 토큰을 폐기한다.
set -euo pipefail

J=http://127.0.0.1:8080          # Nginx 를 거치지 않는다 (인증 요청 제한·외부 노출 없음)
P=$(sed -n 's/^JENKINS_DEPLOY_PASSWORD=//p' /etc/jenkins/casc.env)
[[ -n $P ]] || { echo "/etc/jenkins/casc.env 에 JENKINS_DEPLOY_PASSWORD 가 없음" >&2; exit 1; }

jar=$(mktemp)
trap 'rm -f "$jar"' EXIT

# 비밀번호를 명령줄 인자로 넘기면 실행 중에 프로세스 목록(ps)에 보인다. curl 설정을 표준 입력(-K -)으로 준다
auth() { printf 'user = "github-actions:%s"\n' "$P"; }

# 비밀번호 인증으로 POST 하려면 같은 세션의 CSRF 토큰(crumb)이 필요하다
crumb=$(auth | curl -fsS -K - -c "$jar" "$J/crumbIssuer/api/json" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"] + ":" + d["crumb"])')

auth | curl -fsS -K - -b "$jar" -H "$crumb" -X POST \
    --data-urlencode "newTokenName=github-actions-cd-$(date +%Y%m%d)" \
    "$J/user/github-actions/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["tokenValue"], end="")'
