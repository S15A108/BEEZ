# 3. DB 덤프

## 3-1. 제출 파일

| 파일 | 내용 | 만든 곳 |
| --- | --- | --- |
| `beez-dump.sql` (제출할 때 따로 첨부) | PostgreSQL 17 `pg_dump` SQL 파일. 테이블 구조와 시연 데이터 | 개발 서버, 제출 직전 |

덤프 파일은 저장소에 커밋하지 않고 제출 채널에 따로 올립니다. GitHub 저장소가 공개이고 GitLab은 그 복사본이라,
커밋하면 가입한 사람의 이메일과 비밀번호 해시가 공개되기 때문입니다.

테이블 구조만 필요하면 덤프가 없어도 됩니다. backend가 처음 뜰 때 Flyway가 V52까지의 마이그레이션을 차례로 적용해
빈 DB에 최신 테이블을 만듭니다. 테이블 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)와
`docs/design/erd-core.png`, `docs/design/erd-others.png`에 있습니다.

## 3-2. 덤프 만들기 (서버에서)

서버에 접속해 아래 명령을 실행합니다. 홈 폴더에 `beez-dump.sql`이 생기고, 덤프를 만든 커밋도 함께 기록합니다.

```bash
U=$(grep -E '^DB_USERNAME=' /opt/beez/.env.dev | cut -d= -f2-)
docker exec beez-postgres-1 pg_dump -U "$U" -d beez --no-owner --no-privileges > ~/beez-dump.sql
git -C /opt/beez/src rev-parse HEAD > ~/beez-dump.commit
ls -lh ~/beez-dump.sql; cat ~/beez-dump.commit
```

- `--no-owner --no-privileges`는 다른 계정 이름으로 복원해도 권한 오류가 나지 않게 해 줍니다.
- 우리 팀 서버는 자동 배포라 소스를 클론해 두지 않으므로 `git -C` 줄이 실패합니다. 그때는 아래 명령이 보여 주는
  이미지 태그(커밋 SHA)를 `~/beez-dump.commit`에 적어 둡니다.
  `docker inspect beez-backend-1 --format '{{.Config.Image}}'`
- 만든 파일은 PC에서 `scp <서버>:~/beez-dump.sql .`로 가져옵니다. 제출할 때 덤프를 만든 커밋도 함께 적어 주세요.

## 3-3. 덤프로 복원하기 (PC에서)

> **주의: 1)의 첫 명령은 PC에 있는 BEEZ 로컬 데이터(가입한 계정, 올린 문서)를 모두 지웁니다.** 남겨야 할 데이터가
> 있으면 먼저 3-2와 같은 방법으로 로컬 DB를 덤프해 두세요.

준비할 것:

- 저장소를 클론하고, **덤프를 만든 커밋으로 체크아웃**합니다(`git checkout <커밋>`). 코드가 덤프보다 새로우면 backend가
  뜰 때 마이그레이션이 더 적용되고, 오래되면 backend가 DB를 알아보지 못해 뜨지 않을 수 있습니다.
- [README "빠르게 실행하기"](../README.md#빠르게-실행하기)의 1번(프론트엔드 빌드)을 먼저 마칩니다. 건너뛰면 화면이 비어 보입니다.
- 덤프 파일을 저장소 폴더에 `beez-dump.sql`로 둡니다.

Git Bash에서 저장소 폴더에 있는 상태로 실행합니다.

```bash
# 1) 기존 로컬 데이터를 지우고, DB만 먼저 띄웁니다
docker compose -p beez down -v
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait postgres

# 2) 덤프를 넣습니다. 오류가 나면 그 자리에서 멈춥니다
docker exec -i beez-postgres-1 psql -v ON_ERROR_STOP=1 -q -U beez -d beez < beez-dump.sql

# 3) 나머지 서비스를 띄웁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait
```

2)에서 `ERROR`가 나오고 멈췄다면 1)부터 다시 실행하세요. 일부만 들어간 DB로는 backend가 제대로 동작하지 않습니다.

<http://localhost:8088>에서 덤프 속 계정으로 로그인해 데이터가 보이는지 확인합니다.

- 문서 원본 파일은 DB가 아니라 SeaweedFS에 따로 저장됩니다. 덤프만 복원하면 문서 목록과 내용은 보이지만, 원본 파일
  내려받기는 실패합니다. 원본까지 옮기려면 [docs/infra/seaweedfs.md](../docs/infra/seaweedfs.md)의 백업과 복원 절차를 따르세요.
- 덤프에는 비밀번호가 해시로만 들어 있어서 덤프만 보고는 로그인할 수 없습니다. 시연 계정의 비밀번호는 제출 채널로 따로
  전달합니다.
