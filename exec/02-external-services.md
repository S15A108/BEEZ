# 2. 외부 서비스

BEEZ가 쓰는 외부 서비스와, 다른 환경에서 쓰려면 무엇을 가입하고 발급받아야 하는지 정리했습니다.
소셜 로그인은 쓰지 않습니다. 회원가입은 이메일 인증으로 하는 자체 계정입니다.

## 요약

| 서비스 | 어디에 쓰나 | 없으면 | 설정하는 곳 |
| --- | --- | --- | --- |
| SSAFY GMS (LLM API) | 문서 검토, 보완 질문, 설계 초안, 계획 검증, 비비 질문, 회의 정리, 영향 분석, 문서 검색용 임베딩 | 서비스는 뜨지만 AI 기능을 쓸 수 없습니다 | env 파일 `GMS_KEY` |
| Claude Code (Anthropic) | BEEZ에 연결해 작업 계획을 올리는 AI 에이전트. 시연 ②에 필요합니다 | 에이전트 연결과 시연 ②를 할 수 없습니다. 웹 화면은 그대로 씁니다 | 시연 PC ([2-7](#2-7-ai-에이전트-claude-code)) |
| Anthropic API | GitHub PR 자동 리뷰(`.github/workflows/ai-review.yml`) | PR 자동 리뷰만 돌지 않습니다 | GitHub 저장소 시크릿 `CLAUDE_CODE_OAUTH_TOKEN` 또는 `ANTHROPIC_API_KEY` |
| SMTP 메일 계정 | 가입 인증 코드, 비밀번호 찾기, 팀 초대 메일 | 서버에서 회원가입과 초대를 할 수 없습니다. PC 실행은 메일 대신 로그를 쓰므로 필요 없습니다 | env 파일 `MAIL_*` |
| Let's Encrypt | HTTPS 인증서 | Nginx 설정이 HTTPS를 전제로 해서 사이트에 접속할 수 없습니다 | 서버 Nginx |
| GitHub Actions, GitHub 컨테이너 저장소(GHCR) | 테스트, 이미지 빌드와 보관 | 자동 배포만 안 됩니다. [직접 배포](01-build-and-deploy.md#1-4-새-서버에-배포하기)는 가능합니다 | GitHub 저장소 설정 |
| Jenkins (서버에 직접 설치) | 서버 배포 실행 | 자동 배포만 안 됩니다 | 서버 `/etc/jenkins/casc.env` |
| SSAFY GitLab | GitHub 저장소를 그대로 복사해 두는 곳 | 서비스와 무관합니다 | GitHub 저장소 설정 |
| Mattermost | 서버, DB 이상 알림 받기 | 알림만 오지 않습니다 | 서버 `/opt/beez/.alertmanager-webhook-url` |

## 2-1. LLM API (SSAFY GMS)

BEEZ의 ai 서버는 SSAFY가 교육생에게 제공하는 GMS를 거쳐 OpenAI 모델을 부릅니다.

### 키 발급

<https://gms.ssafy.io>에 SSAFY 교육생 계정으로 로그인해 API 키를 발급받습니다. SSAFY 교육생이 아니면 키를 받을 수 없습니다.

### SSAFY 밖에서 쓰려면

GMS 주소가 코드에 고정된 곳이 많아서, 일반 OpenAI 키로 바꿔 쓰려면 아래를 고쳐야 합니다.

1. 주소가 상수로 박힌 곳을 OpenAI 주소로 바꿉니다.
   - `ai/app/routers/` 안 6개 파일의 `_GMS_CHAT` 상수: `bee_chat.py`(비비 질문), `planning_questions.py`(보완 질문),
     `design_drafts.py`(설계 초안), `meeting_analysis.py`(회의 정리), `design_recommendations.py`, `assignment_recommendations.py`
   - `ai/app/routers/validation.py`의 `GMS_CHAT_URL` 상수 (작업 계획 검증)
   - `ai/app/services/retrieval/gms.py`의 `GMS_EMBEDDINGS_URL` (문서 검색용 임베딩)
2. `ai/app/routers/document_review.py`의 `_valid_gms_endpoint` 함수에 있는 `gms.ssafy.io` 주소 검사를 고칩니다. 이 검사는 문서 검토, 작업 분해,
   작업 계획 초안, 작업 계획 수정, 문서 수정 초안, 회의 수정 초안 6개 기능이 함께 씁니다.
3. env 파일에 `GMS_CHAT_ENDPOINT=<OpenAI 채팅 주소>`를 넣고, `GMS_KEY`에는 OpenAI 키를 넣습니다. 2번의 기능들은 이 주소를 씁니다.
4. 모델 이름은 `ai/app/core/config.py`에 하나로 고정되어 있습니다(24줄 임베딩, 27줄 생성). 쓰려는 공급자에 같은 이름의
   모델이 없으면 이 두 줄의 허용값과 기본값을 고칩니다.

`ai/app/routers/impact.py`의 `_GMS_CHAT`은 호출 주소가 아니라 "GMS 주소일 때만 `GMS_KEY`를 쓴다"는 비교에 쓰이니
고치지 마세요. 변경 영향 분석은 코드를 고치지 않고 env 파일에 `IMPACT_LLM_ENDPOINT`(OpenAI 호환 주소)와
`IMPACT_LLM_API_KEY`(그 주소의 키)를 함께 넣으면 다른 주소로 바꿀 수 있습니다.

### 쓰는 모델

| 용도 | 모델 | 이름이 정해진 곳 |
| --- | --- | --- |
| 문서 검색용 임베딩 | `text-embedding-3-small` (1024차원) | `ai/app/core/config.py`에 고정. 바꾸려면 코드 수정 |
| 그 밖의 AI 기능(문서 검토, 보완 질문, 비비 질문, 계획 검증, 회의 정리 등) | `gpt-6-luna` | `ai/app/core/config.py`에 고정. 바꾸려면 코드 수정 |
| 변경 영향 분석 | `gpt-6-luna` | env 파일 `IMPACT_LLM_MODEL` |

### 키를 넣는 곳

- 서버: `/opt/beez/.env.dev`의 `GMS_KEY=` 뒤에 붙이고 서비스를 다시 띄웁니다. 이미 운영 중인 서버라면
  [docs/infra/cd.md "3. GMS 키 넣기·바꾸기"](../docs/infra/cd.md#3-gms-키-넣기바꾸기)의 명령을 쓰면 키가 화면에 남지 않습니다.
- PC 전체 실행: [README "AI 기능 켜기"](../README.md#4-ai-기능-켜기)대로 터미널에만 넣습니다.
- ai만 따로 실행: `ai/.env`에 넣습니다.

키는 저장소, `VITE_`로 시작하는 변수, 메신저에 남기지 마세요.

## 2-2. 메일 (SMTP)

가입 인증 코드, 비밀번호 찾기, 팀 초대 메일을 보냅니다.

1. SMTP를 지원하는 메일 계정을 준비합니다(Gmail, 네이버 메일 등).
2. 메일 계정에서 SMTP를 쓸 수 있게 설정합니다.
   - Gmail: Google 계정에서 2단계 인증을 켜고 **앱 비밀번호**를 발급받습니다.
   - 네이버 메일: 메일 환경설정의 POP3/IMAP 설정에서 SMTP 사용을 켜고, 2단계 인증을 쓰는 계정이면 애플리케이션 비밀번호를 발급받습니다.
3. 서버 env 파일 `/opt/beez/.env.dev`의 `MAIL_` 줄을 채웁니다.

   ```text
   MAIL_HOST=<SMTP 서버, 예: smtp.gmail.com 또는 smtp.naver.com>
   MAIL_PORT=587
   MAIL_USERNAME=<메일 주소>
   MAIL_PASSWORD=<앱 비밀번호>
   MAIL_FROM=BEEZ <메일 주소>
   ```

   `MAIL_FROM`의 주소는 `MAIL_USERNAME`과 똑같이 적으세요. 다르면 메일 서버가 발송을 거절합니다.
4. 서비스를 다시 띄워 바뀐 값을 적용합니다.

   ```bash
   cd /opt/beez/src && docker compose -f infra/compose.yaml --env-file /opt/beez/.env.dev up -d --wait
   ```

### 발송 횟수 제한

서버 전체는 한 시간에 100통(`EMAIL_VERIFY_GLOBAL_SEND_LIMIT`), 같은 IP에서는 한 시간에 10번(`AUTH_IP_LIMIT`)까지
인증 메일을 요청할 수 있습니다. 시연장처럼 여러 사람이 같은 와이파이에서 가입한다면 env 파일에 `AUTH_IP_LIMIT=50` 줄을
넣고 위 4번 명령으로 서비스를 다시 띄우세요.

## 2-3. HTTPS 인증서 (Let's Encrypt)

따로 가입할 필요가 없습니다. 도메인이 서버 IP를 가리키게 해 두고, 서버에서 certbot으로 인증서를 받습니다. 인증서는
자동으로 갱신됩니다. 받는 순서는 [1-4절의 6)](01-build-and-deploy.md#6-nginx-설정과-https-인증서)에 있습니다.

## 2-4. 자동 배포 (GitHub Actions, GHCR, Jenkins)

팀 저장소에서만 쓰는 자동화입니다. 새 환경에서는 없어도 [직접 배포](01-build-and-deploy.md#1-4-새-서버에-배포하기)할 수 있습니다.

| 항목 | 하는 일 |
| --- | --- |
| GitHub Actions `ci.yml` | PR마다 frontend, backend, ai 테스트를 돌립니다. 비밀값을 쓰지 않습니다 |
| GitHub Actions `cd.yml` | dev에 push되면 이미지를 빌드해 GHCR(`ghcr.io/s15a108/*`)에 올리고 Jenkins에 배포를 요청합니다 |
| GHCR | 빌드한 이미지를 보관합니다. 공개로 설정되어 있어 서버는 로그인 없이 이미지를 받습니다 |
| Jenkins | 서버에서 `infra/deploy/deploy.sh`를 실행해 새 이미지로 교체합니다 |

설정 방법은 [docs/infra/cd.md](../docs/infra/cd.md)와 [docs/infra/jenkins.md](../docs/infra/jenkins.md)에 있습니다.

## 2-5. SSAFY GitLab

GitHub(`S15A108/BEEZ`)에 어느 브랜치나 태그를 push하든 GitHub Actions(`sync-gitlab.yml`)가 같은 커밋을
`https://lab.ssafy.com/s15-final/S15P31A108`에 복사합니다. 설정은
[docs/gitlab-sync.md](../docs/gitlab-sync.md)에 있습니다.

## 2-6. 알림 (Mattermost)

서버, DB, 문서 분석에 문제가 생기면 Mattermost 채널로 알림을 보냅니다. 서비스가 동작하는 데는 필요 없습니다.
웹훅 주소는 서버의 `/opt/beez/.alertmanager-webhook-url` 파일에만 둡니다. 넣는 방법은
[docs/infra/monitoring-alerts.md "웹훅 넣기"](../docs/infra/monitoring-alerts.md#웹훅-넣기-서버당-한-번)에 있습니다.

## 2-7. AI 에이전트 (Claude Code)

BEEZ에 연결해 쓰는 AI 에이전트입니다. 시연 ②처럼 에이전트가 계획을 올리는 장면을 보이려면 필요합니다.

1. Anthropic 계정(Claude 유료 요금제 또는 API 결제)을 준비합니다.
2. Node.js 24가 설치된 PC에서 Claude Code를 설치합니다.

   ```bash
   npm install -g @anthropic-ai/claude-code
   ```

3. 터미널에서 `claude`를 실행하고 안내에 따라 Anthropic 계정으로 로그인합니다.
4. BEEZ 연결은 [README "에이전트 연결하기"](../README.md#에이전트-연결하기)대로 합니다.
