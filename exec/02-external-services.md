# 2. 외부 서비스

BEEZ가 쓰는 외부 서비스와, 다른 환경에서 쓰려면 무엇을 가입하고 발급받아야 하는지 정리했습니다.
소셜 로그인은 쓰지 않습니다. 회원가입은 이메일 인증으로 하는 자체 계정입니다.

## 요약

| 서비스 | 어디에 쓰나 | 없으면 | 설정하는 곳 |
| --- | --- | --- | --- |
| SSAFY GMS (LLM API) | 문서 검토, 보완 질문, 설계 초안, 계획 검증, 비비 질문, 회의 정리, 영향 분석, 문서 검색용 임베딩 | 서비스는 뜨지만 AI 기능을 쓸 수 없습니다 | env 파일 `GMS_KEY` |
| SMTP 메일 계정 | 가입 인증 코드, 비밀번호 찾기, 팀 초대 메일 | 서버에서 회원가입과 초대를 할 수 없습니다. PC 실행은 메일 대신 로그를 쓰므로 필요 없습니다 | env 파일 `MAIL_*` |
| Let's Encrypt | HTTPS 인증서 | HTTP로만 접속됩니다 | 서버 Nginx |
| GitHub Actions, GHCR | 테스트, 이미지 빌드와 보관 | 자동 배포만 안 됩니다. [직접 배포](01-build-and-deploy.md#1-4-새-서버에-배포하기)는 가능합니다 | GitHub 저장소 설정 |
| Jenkins (서버에 직접 설치) | 서버 배포 실행 | 자동 배포만 안 됩니다 | 서버 `/etc/jenkins/casc.env` |
| SSAFY GitLab | GitHub 저장소를 그대로 복사해 두는 곳 | 서비스와 무관합니다 | GitHub 저장소 설정 |
| Mattermost | 서버, DB 이상 알림 받기 | 알림만 오지 않습니다 | 서버 파일 |

## 2-1. LLM API — SSAFY GMS

BEEZ의 ai 서버는 SSAFY가 교육생에게 제공하는 GMS를 거쳐 OpenAI 모델을 부릅니다.

- **발급**: SSAFY 교육생 계정으로 GMS에 들어가 API 키를 발급받습니다.
- **SSAFY 밖에서는**: 키를 발급받을 수 없고, ai 서버가 GMS 주소만 받도록 되어 있어 일반 OpenAI 키로 바꿔 쓸 수도 없습니다.
  SSAFY 밖에서 AI 기능을 쓰려면 `ai/app/core/config.py`의 주소 검사와 AI 라우터의 GMS 주소를 고쳐야 합니다.
- **쓰는 모델**

  | 용도 | 모델 |
  | --- | --- |
  | 문서 검색용 임베딩 | `text-embedding-3-small` (1024차원) |
  | 일반 생성 | `gpt-6-luna` |
  | 복잡한 생성 | `gpt-6.1-sol` |
  | 변경 영향 분석 | `gpt-6-luna` |

- **넣는 곳**
  - 서버: `/opt/beez/.env.dev`의 `GMS_KEY=` 뒤에 붙입니다. 이미 떠 있는 서버라면 [docs/infra/cd.md "3. GMS 키 넣기·바꾸기"](../docs/infra/cd.md#3-gms-키-넣기바꾸기)의 명령을 쓰세요.
  - PC 전체 실행: [README "AI 기능까지 쓰려면"](../README.md#ai-기능까지-쓰려면)대로 터미널에만 넣습니다.
  - ai만 따로 실행: `ai/.env`에 넣습니다.
- 키는 저장소, `VITE_`로 시작하는 변수, 메신저에 남기지 마세요.

## 2-2. 메일 — SMTP

가입 인증 코드, 비밀번호 찾기, 팀 초대 메일을 보냅니다.

1. SMTP를 지원하는 메일 계정을 준비합니다(Gmail, 네이버 메일 등).
2. 메일 계정에서 2단계 인증을 켜고 **앱 비밀번호**를 발급받습니다. 로그인 비밀번호가 아니라 이 값을 씁니다.
3. 서버 env 파일 `/opt/beez/.env.dev`에 아래 줄을 넣고 서비스를 다시 띄웁니다
   (`docker compose -f infra/compose.yaml --env-file /opt/beez/.env.dev up -d --wait`).

   ```text
   MAIL_HOST=<SMTP 서버, 예: smtp.gmail.com>
   MAIL_PORT=587
   MAIL_USERNAME=<메일 주소>
   MAIL_PASSWORD=<앱 비밀번호>
   MAIL_FROM=BEEZ <메일 주소>
   ```

   `MAIL_FROM`의 주소는 `MAIL_USERNAME`과 똑같이 적으세요. 다르면 메일 서버가 발송을 거절합니다.

**발송 횟수 제한**: 서버 전체는 한 시간에 100통(`EMAIL_VERIFY_GLOBAL_SEND_LIMIT`), 같은 IP에서는 한 시간에 10번
(`AUTH_IP_LIMIT`)까지 인증 메일을 요청할 수 있습니다. 시연장처럼 여러 사람이 같은 와이파이에서 가입한다면
`AUTH_IP_LIMIT=50`처럼 늘려 두세요.

## 2-3. HTTPS 인증서 — Let's Encrypt

따로 가입할 필요가 없습니다. 도메인이 서버 IP를 가리키게 해 두고, 서버에서 certbot으로 인증서를 받습니다. 인증서는
자동으로 갱신됩니다. 명령은 [docs/infra/nginx-https.md](../docs/infra/nginx-https.md) 3절에 있습니다.

## 2-4. 자동 배포 — GitHub Actions, GHCR, Jenkins

팀 저장소에서만 쓰는 자동화입니다. 새 환경에서는 없어도 [직접 배포](01-build-and-deploy.md#1-4-새-서버에-배포하기)할 수 있습니다.

| 항목 | 하는 일 |
| --- | --- |
| GitHub Actions `ci.yml` | PR마다 frontend, backend, ai 테스트를 돌립니다. 비밀값을 쓰지 않습니다 |
| GitHub Actions `cd.yml` | dev에 push되면 이미지를 빌드해 GHCR(`ghcr.io/s15a108/*`)에 올리고 Jenkins에 배포를 요청합니다 |
| GHCR | 빌드한 이미지를 보관합니다. 공개 저장소라 서버는 로그인 없이 이미지를 받습니다 |
| Jenkins | 서버에서 `infra/deploy/deploy.sh`를 실행해 새 이미지로 교체합니다 |

설정 방법은 [docs/infra/cd.md](../docs/infra/cd.md)와 [docs/infra/jenkins.md](../docs/infra/jenkins.md)에 있습니다.

## 2-5. SSAFY GitLab

GitHub(`S15A108/BEEZ`)의 `main`, `dev` 브랜치에 push되면 GitHub Actions(`sync-gitlab.yml`)가 같은 커밋을 SSAFY GitLab에
복사합니다. GitLab에는 직접 push하지 마세요. 설정은 [docs/gitlab-sync.md](../docs/gitlab-sync.md)에 있습니다.

## 2-6. 알림 — Mattermost

서버, DB, 문서 분석에 문제가 생기면 Mattermost 채널로 알림을 보냅니다. 서비스가 동작하는 데는 필요 없습니다.
설정은 [docs/infra/monitoring-alerts.md](../docs/infra/monitoring-alerts.md)에 있습니다.
