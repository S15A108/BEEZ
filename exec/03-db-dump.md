# 3. DB 덤프

## 3-1. 제출 파일

| 파일 | 내용 | 만든 곳 | 덤프를 만든 커밋 |
| --- | --- | --- | --- |
| `beez-dump.sql` (제출할 때 따로 첨부) | PostgreSQL 17 `pg_dump` SQL 파일. 테이블 구조와 시연 데이터 | 팀 개발 서버, 시연 버튼을 누르기 전 상태 | (제출할 때 기입: `beez-dump.commit`의 SHA) |

덤프 파일은 저장소에 커밋하지 않고 제출 채널에 따로 올립니다. GitHub 저장소가 공개이고 GitLab은 그 복사본이라,
커밋하면 가입한 사람의 이메일과 비밀번호 해시가 공개되기 때문입니다.

테이블 구조만 필요하면 덤프가 없어도 됩니다. backend가 처음 뜰 때 Flyway(DB 테이블 생성 도구)가 최신 마이그레이션까지 차례로 적용해
빈 DB에 최신 테이블을 만듭니다. 테이블 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)와
`docs/design/erd-core.png`, `docs/design/erd-others.png`에 있습니다.

## 3-2. 덤프 만들기 (서버에서)

### 언제 만드나

[시연 시나리오](04-demo-scenario.md)의 ①기준 발행, ④회의 끝내기, ⑤기준 v2 발행은 되돌릴 수 없습니다. 발표를 마친
뒤의 DB를 덤프하면 이 버튼들이 이미 눌린 상태로 저장되어, 덤프를 복원한 사람이 시나리오를 따라 할 수 없습니다.
그래서 시연 덤프는 **시연 버튼을 누르기 전이고 "발표 2분 전" 에이전트 요청도 보내기 전인 상태**에서 만듭니다.
발표 뒤에 다시 만들지 마세요.

### 명령

팀 개발 서버에 접속해 아래 명령을 실행합니다. 홈 폴더에 덤프 파일과, 덤프를 만든 코드 버전(이미지 태그에 든 커밋 SHA)을
적은 파일이 생깁니다. 이미지 이름 `ghcr.io/s15a108/beez-backend:<SHA>`에서 SHA만 남깁니다.

```bash
U=$(grep -E '^DB_USERNAME=' /opt/beez/.env.dev | cut -d= -f2-)
docker exec beez-postgres-1 pg_dump -U "$U" -d beez --no-owner --no-privileges > ~/beez-dump.sql
docker inspect beez-backend-1 --format '{{.Config.Image}}' | sed 's/.*://' > ~/beez-dump.commit
ls -lh ~/beez-dump.sql; cat ~/beez-dump.commit
```

- `--no-owner --no-privileges`는 다른 계정 이름으로 복원해도 권한 오류가 나지 않게 해 줍니다.
- 1-4절처럼 직접 배포한 서버에서는 이미지 태그에 커밋이 없으니, 셋째 줄 대신
  `git -C /opt/beez/src rev-parse HEAD > ~/beez-dump.commit`을 실행합니다.

### PC로 가져오기

PC(Git Bash)에서 두 파일을 가져온 뒤, 개인정보가 든 덤프가 서버에 남지 않게 지웁니다.

```bash
scp <서버>:~/beez-dump.sql <서버>:~/beez-dump.commit .
ssh <서버> 'rm ~/beez-dump.sql ~/beez-dump.commit'
```

`beez-dump.commit`에 적힌 SHA를 3-1 표의 "덤프를 만든 커밋" 칸에 적어 제출합니다.

## 3-3. 덤프로 복원하기 (PC에서)

> **주의: 1)의 첫 명령은 PC에 있는 BEEZ 로컬 데이터(가입한 계정, 올린 문서)를 모두 지웁니다.** 남겨야 할 데이터가
> 있으면 먼저 3-2와 같은 방법으로 로컬 DB를 덤프해 두세요.

준비할 것:

- 저장소를 클론하고, 3-1 표의 "덤프를 만든 커밋"으로 체크아웃합니다(`git checkout <커밋>`). 코드가 덤프보다 새로우면 backend가
  뜰 때 마이그레이션이 더 적용되고, 오래되면 backend가 DB를 알아보지 못해 뜨지 않을 수 있습니다.
- [README "빠르게 실행하기"](../README.md#빠르게-실행하기) 2)의 첫 명령(프론트엔드 빌드)을 먼저 실행합니다. 건너뛰면 화면이 비어 보입니다.
- 덤프 파일을 저장소 폴더에 `beez-dump.sql`로 둡니다.

Git Bash에서 저장소 폴더에 있는 상태로 실행합니다.

```bash
# 1) 기존 로컬 데이터를 지우고, DB만 먼저 띄웁니다
docker compose -p beez down -v
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait postgres

# 2) 덤프를 넣습니다. 오류가 나면 그 자리에서 멈춥니다
docker exec -i beez-postgres-1 psql -v ON_ERROR_STOP=1 -q -U beez -d beez < beez-dump.sql

# 3) 나머지 서비스를 띄웁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --build --wait
```

3)의 `--build`는 지금 체크아웃한 커밋으로 이미지를 다시 만듭니다. 빼면 예전에 빌드한 이미지가 그대로 떠서 코드 버전이
덤프와 달라질 수 있습니다.

2)에서 `ERROR`가 나오고 멈췄다면 1)부터 다시 실행하세요. 일부만 들어간 DB로는 backend가 제대로 동작하지 않습니다.

<http://localhost:8088>에서 덤프 속 계정으로 로그인해 데이터가 보이는지 확인합니다.

- 문서 원본 파일은 DB가 아니라 SeaweedFS에 따로 저장됩니다. 덤프만 복원하면 문서 목록과 내용은 보이지만, 원본 파일
  내려받기는 실패합니다. 원본까지 옮기려면 [docs/infra/seaweedfs.md](../docs/infra/seaweedfs.md)의 백업과 복원 절차를 따르세요.
- 덤프에는 비밀번호가 해시로만 들어 있어서 덤프만 보고는 로그인할 수 없습니다. 시연 계정의 비밀번호는 제출 채널로 따로
  전달합니다.
