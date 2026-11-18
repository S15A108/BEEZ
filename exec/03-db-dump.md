# 3. DB 덤프

## 3-1. 제출 파일

| 파일 | 내용 | 만든 곳 | 덤프를 만든 서버 커밋 |
| --- | --- | --- | --- |
| [`exec/beez-db-dump.sql.gz`](beez-db-dump.sql.gz) | PostgreSQL 17 `pg_dump` SQL 파일을 gzip으로 압축한 것(약 23MB, 풀면 약 80MB). 테이블 구조와 시연 데이터 | 2026-11-17 팀 개발 서버 DB를 비식별화한 것 | `2d27e1d078213aecb38c9cf027b92acc1c963b58` |

이 덤프는 저장소가 공개라서 **비식별화한 것**입니다. 개발 서버 DB에서 아래를 바꾸거나 지웠습니다.

| 항목 | 처리 |
| --- | --- |
| 계정 이메일 | `user<계정 번호>@example.com` |
| 계정 비밀번호 | 모든 계정을 시연용 비밀번호 **`beez-demo-2026`** 하나로 통일 |
| 계정 이름 | 시연 계정(전진, 백엔드진)만 그대로 두고 나머지는 `사용자<계정 번호>` |
| 로그인 세션, 가입 인증, 임시 비밀번호 | 모두 지움 |
| 초대 | 받는 사람 이메일을 `invitee<번호>@example.com`으로, 초대 링크는 쓸 수 없게 바꿈 |
| 에이전트 토큰 | 원래 토큰으로는 접속할 수 없게 바꿈. 에이전트는 복원한 뒤 새 토큰을 발급해 연결합니다 |

| 문서·작업 계획·AI 기록 본문의 이메일 | `example.com`, `example.test` 같은 예시 주소가 아니면 `redacted@example.com` |

프로젝트, 문서, 설계, 작업, 회의 같은 업무 데이터는 그대로 있습니다. 비식별화에 쓴 SQL은
[`exec/dump/sanitize.sql`](dump/sanitize.sql)입니다. 압축한 이유는 문서 검색용 임베딩(`document_chunk`)이
원본의 70%를 차지해 SQL 그대로는 80MB가 넘기 때문입니다.

덤프는 시연 시나리오의 버튼을 누르기 전 상태입니다. 시연 계정은 아래 둘이고, 비밀번호는 모두 `beez-demo-2026`입니다.

| 계정 | 로그인 이메일 |
| --- | --- |
| 팀장 전진 | `user7@example.com` |
| 백엔드 담당 백엔드진 | `user11@example.com` |

테이블 구조만 필요하면 덤프가 없어도 됩니다. backend가 처음 뜰 때 빈 DB에 최신 테이블을 자동으로 만듭니다
([1-6의 7번](01-build-and-deploy.md#1-6-배포할-때-주의할-점)). 테이블 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)와
`docs/design/erd-core.png`, `docs/design/erd-others.png`에 있습니다.

## 3-2. 덤프로 복원하기 (PC에서)

> **주의: 1)의 첫 명령은 PC에 있는 BEEZ 로컬 데이터(가입한 계정, 올린 문서)를 모두 지웁니다.** 남겨야 할 데이터가
> 있으면 먼저 지금 로컬 DB를 덤프해 두세요.

준비할 것:

- 저장소를 받습니다([README "소스 받기"](../README.md#1-소스-받기)). 덤프를 만든 커밋과 같거나 더 새로운 코드면 됩니다.
  더 새로운 코드라면 backend가 뜰 때 덤프 이후에 추가된 마이그레이션을 이어서 적용합니다.
- [README "빠르게 실행하기"](../README.md#빠르게-실행하기) 2)의 첫 명령(프론트엔드 빌드)을 먼저 실행합니다. 건너뛰면 화면이 비어 보입니다.

Git Bash에서 저장소 폴더에 있는 상태로 아래 블록을 통째로 붙여 넣어 실행합니다. 괄호로 묶여 있어, 어느 단계든 실패하면
그 자리에서 멈추고 다음 단계로 넘어가지 않습니다.

```bash
(
  set -euo pipefail

  # 1) 기존 로컬 데이터를 지우고, DB만 먼저 띄웁니다
  docker compose -p beez down -v
  docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait postgres

  # 2) 덤프를 넣습니다
  gunzip -c exec/beez-db-dump.sql.gz | docker exec -i beez-postgres-1 psql -v ON_ERROR_STOP=1 -q -U beez -d beez > /dev/null

  # 3) 나머지 서비스를 띄웁니다
  docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
)
```

중간에 오류가 나고 멈췄다면 원인을 고친 뒤 블록을 처음부터 다시 실행하세요. 1)이 DB를 비우고 시작하므로, 일부만 들어간
DB가 남지 않습니다.

2)에서 `wal_level` 경고가 한 줄 나올 수 있는데, 무시해도 됩니다. 개발 서버의 변경 감지(CDC) 설정이 덤프에 함께 들어
있어서 나오는 안내입니다.

<http://localhost:8088>에서 3-1의 시연 계정(`user7@example.com`, 비밀번호 `beez-demo-2026`)으로 로그인합니다.

AI 기능까지 쓰려면 이어서 [README "AI 기능 켜기"](../README.md#4-ai-기능-켜기)를 실행하세요. 새 터미널에서 띄우면
AI 기능은 꺼진 채로 뜹니다.

문서 원본 파일은 DB가 아니라 SeaweedFS에 따로 저장됩니다. 덤프만 복원하면 문서 목록과 내용은 보이지만, 원본 파일
내려받기는 실패합니다. 원본까지 옮기려면 [docs/infra/seaweedfs.md](../docs/infra/seaweedfs.md)의 백업과 복원 절차를 따르세요.

## 3-3. 덤프 새로 만들기 (팀용)

제출 덤프를 다시 만들 때의 절차입니다. 개발 서버 DB에는 실제 이메일과 비밀번호 해시가 들어 있으니, **원본 덤프는
저장소에 넣지 말고** 비식별화한 결과만 커밋합니다.

### 언제 만드나

[시연 시나리오](04-demo-scenario.md)의 ①기준 발행, ④회의 끝내기, ⑤기준 v2 발행은 되돌릴 수 없습니다. 이 버튼들을
누르기 전이고 "발표 2분 전" 에이전트 요청도 보내기 전인 상태에서 만들어야, 덤프를 복원한 사람이 시나리오를 처음부터
따라 할 수 있습니다.

### 1) 서버에서 원본 덤프 만들기

팀 개발 서버에 접속해 실행합니다. 홈 폴더에 원본 덤프와, 덤프를 만든 코드 버전(이미지 태그의 커밋 SHA)을 적은 파일이
생깁니다.

```bash
U=$(grep -E '^DB_USERNAME=' /opt/beez/.env.dev | cut -d= -f2-)
docker exec beez-postgres-1 pg_dump -U "$U" -d beez --no-owner --no-privileges > ~/beez-dump.sql
docker inspect beez-backend-1 --format '{{.Config.Image}}' | sed 's/.*://' > ~/beez-dump.commit
ls -lh ~/beez-dump.sql; cat ~/beez-dump.commit
```

1-4절처럼 직접 배포한 서버에서는 이미지 태그에 커밋이 없으니, 셋째 줄 대신
`git -C /opt/beez/src rev-parse HEAD > ~/beez-dump.commit`을 실행합니다.

### 2) PC로 가져오고 서버에서 지우기

PC(Git Bash)에서 실행합니다. 원본 덤프는 저장소 폴더가 아니라 홈 폴더에 둡니다. `<서버>`는 `ubuntu@서버주소`처럼
SSH로 접속할 대상으로 바꿔 적으세요. 그대로 두면 bash가 `<`를 입력 기호로 읽어 오류가 납니다.

```bash
scp <서버>:~/beez-dump.sql <서버>:~/beez-dump.commit ~/
ssh <서버> 'rm ~/beez-dump.sql ~/beez-dump.commit'
```

### 3) 비식별화해 제출 덤프 만들기

저장소 폴더에서 실행합니다. 원본은 임시 컨테이너 안에서만 다루고, 결과만 `exec/beez-db-dump.sql.gz`로 남깁니다.

```bash
DEMO_PASSWORD='beez-demo-2026' KEEP_NAMES='전진,백엔드진' bash exec/dump/make-public-dump.sh ~/beez-dump.sql
```

- 결과에 `example.com`, `example.test`, `beez.ai.kr`이 아닌 이메일 주소가 남아 있으면 스크립트가 결과 파일을 지우고 그 주소를 보여 줍니다.
  그 주소가 든 테이블을 찾아 `exec/dump/sanitize.sql`에 처리를 더한 뒤 다시 실행하세요.
- 끝나면 `~/beez-dump.commit`의 SHA를 3-1 표의 "덤프를 만든 서버 커밋" 칸에 적고, `exec/beez-db-dump.sql.gz`와 함께 PR로
  올립니다. 스크립트가 마지막에 보여 주는 시연 계정 이메일도 3-1 표에 맞춥니다. 원본 `~/beez-dump.sql`과 `~/beez-dump.commit`은 지웁니다.
