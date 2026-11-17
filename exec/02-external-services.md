# 2. 외부 서비스

BEEZ가 쓰는 외부 서비스와, 다른 환경에서 쓰려면 무엇을 가입·발급해야 하는지 정리합니다.
소셜 로그인은 쓰지 않습니다. 회원 인증은 이메일 인증 기반의 자체 계정입니다.

## 요약

| 서비스 | 용도 | 없으면 | 넣는 곳 |
| --- | --- | --- | --- |
| SSAFY GMS (OpenAI 호환 LLM API) | 문서 검토, 보완 질문, 설계 초안, 계획 검증, 비비, 회의 분석, 영향 분석, 임베딩 | 서비스는 뜨지만 AI 기능이 502 `model_unavailable` | `GMS_KEY` |
| SMTP 메일 | 가입 인증 코드, 비밀번호 찾기, 팀 초대 메일 | prod는 메일 발송이 503 `mail_send_failed` (로컬은 로그로 대체) | `MAIL_*` |
| Let's Encrypt (certbot) | `dev.beez.ai.kr` HTTPS 인증서 | HTTP만 동작 | 서버 Nginx |
| GitHub Actions · GHCR | CI, 이미지 빌드·저장 | 직접 빌드해 배포 | 저장소 설정 |
| Jenkins (서버 자체 설치) | 서버 배포 실행 | `deploy.sh` 수동 실행 | `/etc/jenkins/casc.env` |
| SSAFY GitLab | GitHub → GitLab 단방향 미러 | 영향 없음 | `GITLAB_SYNC_TOKEN` |
| Mattermost 웹훅 | 서버·DB·CDC 알림 수신 | 알림만 없음 | `/opt/beez/.alertmanager-webhook-url` |

## 2-1. LLM API — SSAFY GMS

- **무엇**: SSAFY가 교육생에게 제공하는 OpenAI 호환 API 게이트웨이입니다. BEEZ의 ai 서버가 채팅 완성과 임베딩을 이 주소로
  부릅니다.
- **발급**: SSAFY 교육생 계정으로 GMS에서 API 키를 발급받습니다. SSAFY 외부에서는 발급받을 수 없습니다.
- **주소**: `https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions` (코드 기본값)
- **모델**

  | 용도 | 모델 | 설정 |
  | --- | --- | --- |
  | 임베딩 (문서 색인·검색) | `text-embedding-3-small`, 1024차원 | `GMS_EMBEDDING_MODEL`, `GMS_EMBEDDING_DIMENSION` |
  | 기본 생성 | `gpt-6-luna` | `BEE_DEFAULT_MODEL` |
  | 복잡한 생성 | `gpt-6.1-sol` | `BEE_COMPLEX_MODEL` |
  | 변경 영향 분석 | `gpt-6-luna` | `IMPACT_LLM_MODEL` |

- **넣는 법**: 서버는 `/opt/beez/.env.dev`의 `GMS_KEY`([cd.md "3. GMS 키 넣기·바꾸기"](../docs/infra/cd.md#3-gms-키-넣기바꾸기)),
  로컬 compose는 셸 환경변수 `GMS_KEY`, ai 단독 실행은 `ai/.env`에 넣습니다.
- **주의**: 키는 저장소·`VITE_` 변수·로그에 남기지 않습니다. 임베딩 모델·차원을 바꾸면 backend의
  `DOCUMENT_EMBEDDING_PROFILE`·`DOCUMENT_EMBEDDING_MODEL`도 같이 바꿔야 색인이 동작합니다.

## 2-2. 메일 — SMTP

- **무엇**: 가입 인증 코드(ACC-A11), 비밀번호 찾기(ACC-A07), 팀 초대(MEM-A01) 메일을 보냅니다.
- **발급**: SMTP를 지원하는 메일 계정(예: Gmail·Naver 등)에서 2단계 인증을 켠 뒤 **앱 비밀번호**를 발급받습니다.
- **설정**

  | 이름 | 예 | 설명 |
  | --- | --- | --- |
  | `MAIL_HOST` | `smtp.<제공자>.com` | 비우면 기동은 하고 발송만 실패 |
  | `MAIL_PORT` | `587` | STARTTLS |
  | `MAIL_USERNAME` | 메일 계정 | |
  | `MAIL_PASSWORD` | 앱 비밀번호 | 비밀 |
  | `MAIL_FROM` | `BEEZ <계정 주소>` | **SMTP 계정 주소와 같아야** 제공자가 거부하지 않습니다 |

- **로컬**: local 프로필은 메일을 보내지 않고 인증 코드를 backend 로그에 찍습니다(`MAIL_MODE=log`).
- **발송 상한**: 서버 전체 시간당 `EMAIL_VERIFY_GLOBAL_SEND_LIMIT`(기본 100), IP당 `AUTH_IP_LIMIT`(기본 10),
  초대자당 `INVITE_SEND_LIMIT`(기본 30). 한 공유기 뒤에서 여럿이 가입하는 시연에서는 `AUTH_IP_LIMIT`을 올립니다.

## 2-3. HTTPS 인증서 — Let's Encrypt

- 서버 호스트 Nginx에 certbot으로 `dev.beez.ai.kr` 인증서를 받고 자동 갱신합니다. 가입은 필요 없고 도메인의 A 레코드가
  서버를 가리키면 됩니다.
- 절차: [docs/infra/nginx-https.md "3. 인증서 발급(certbot)"](../docs/infra/nginx-https.md)

## 2-4. CI/CD — GitHub Actions · GHCR · Jenkins

| 항목 | 내용 |
| --- | --- |
| CI | PR마다 `ci.yml`이 frontend·backend·ai 검사를 돌립니다. 비밀값을 쓰지 않습니다 |
| 이미지 저장소 | GHCR `ghcr.io/s15a108/*`. Actions의 `GITHUB_TOKEN`으로 push, 패키지는 공개라 서버는 로그인 없이 pull |
| 배포 | Actions가 서버의 Jenkins 잡 `beez-deploy`를 호출합니다. 토큰은 GitHub Environment `dev`의 `JENKINS_API_TOKEN` |
| 문서 | [docs/infra/cd.md](../docs/infra/cd.md), [docs/infra/jenkins.md](../docs/infra/jenkins.md) |

## 2-5. GitLab 미러

GitHub(`S15A108/BEEZ`)의 `main`·`dev` push를 Actions(`sync-gitlab.yml`)가 SSAFY GitLab으로 단방향 전송합니다.
GitLab에 직접 push하지 않습니다. 토큰은 저장소 시크릿 `GITLAB_SYNC_TOKEN`입니다([docs/gitlab-sync.md](../docs/gitlab-sync.md)).

## 2-6. 알림 — Mattermost

서버 모니터링(Prometheus·Alertmanager·Grafana)의 알림을 Mattermost 웹훅으로 받습니다. 웹훅 주소는 서버 파일에만 둡니다
([docs/infra/monitoring-alerts.md](../docs/infra/monitoring-alerts.md)). 서비스 동작에는 필요하지 않습니다.
