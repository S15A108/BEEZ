# 3. DB 덤프

## 3-1. 제출 파일

| 파일 | 내용 | 만든 곳 |
| --- | --- | --- |
| `beez-dump.sql` (제출할 때 따로 첨부) | PostgreSQL 17 `pg_dump` SQL 파일. 테이블 구조와 시연 데이터 | 개발 서버, 제출 직전 |

> **덤프 파일은 저장소에 커밋하지 마세요.** GitHub 저장소가 공개이고 GitLab은 그 복사본이라, 커밋하면 가입한 사람의
> 이메일과 비밀번호 해시가 공개됩니다. 제출 채널에 파일로 따로 올립니다.

테이블 구조만 필요하면 덤프가 없어도 됩니다. backend가 처음 뜰 때 Flyway가 V1~V52 마이그레이션을 차례로 적용해
빈 DB에 최신 테이블을 만듭니다. 테이블 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)와
`docs/design/erd-core.png`, `docs/design/erd-others.png`에 있습니다.

## 3-2. 덤프 만들기 (서버에서)

서버에 접속해 아래 명령을 실행합니다. 홈 폴더에 날짜가 붙은 파일이 생깁니다.

```bash
U=$(grep -E '^DB_USERNAME=' /opt/beez/.env.dev | cut -d= -f2-)
docker exec beez-postgres-1 pg_dump -U "$U" -d beez --no-owner --no-privileges \
  > ~/beez-dump-$(date +%Y%m%d).sql
ls -lh ~/beez-dump-*.sql
```

- `--no-owner --no-privileges`는 다른 계정 이름으로 복원해도 권한 오류가 나지 않게 해 줍니다.
- 만든 파일은 PC에서 `scp <서버>:~/beez-dump-YYYYMMDD.sql .`로 가져옵니다.

## 3-3. 덤프로 복원하기 (PC에서)

> **주의: 아래 첫 명령은 PC에 있는 BEEZ 로컬 데이터(가입한 계정, 올린 문서)를 모두 지웁니다.** 남겨야 할 데이터가
> 있으면 먼저 3-2와 같은 방법으로 로컬 DB를 덤프해 두세요.

저장소를 클론한 폴더에서 Git Bash로 실행합니다. 덤프 파일은 같은 폴더에 `beez-dump.sql`로 두었다고 가정합니다.

```bash
# 1) 기존 로컬 데이터를 지우고, DB만 먼저 띄웁니다
docker compose -p beez down -v
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait postgres

# 2) 덤프를 넣습니다
docker exec -i beez-postgres-1 psql -U beez -d beez < beez-dump.sql

# 3) 나머지 서비스를 띄웁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait
```

<http://localhost:8088>에서 덤프 속 계정으로 로그인해 데이터가 보이는지 확인합니다.

- 문서 **원본 파일**은 DB가 아니라 SeaweedFS에 따로 저장됩니다. 덤프만 복원하면 문서 목록과 내용은 보이지만, 원본 파일
  내려받기는 실패합니다. 원본까지 옮기려면 [docs/infra/seaweedfs.md](../docs/infra/seaweedfs.md)의 백업과 복원 절차를 따르세요.
- 덤프에는 비밀번호가 해시로만 들어 있어서 덤프만 보고는 로그인할 수 없습니다. 시연 계정의 비밀번호는 제출 채널로 따로
  전달합니다.
