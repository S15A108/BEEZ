# 3. DB 덤프

## 3-1. 제출 파일

| 파일 | 내용 | 기준 |
| --- | --- | --- |
| `beez-dump.sql` (제출 시 별도 첨부) | PostgreSQL 17 `pg_dump` 평문 SQL. 스키마 + 시연 데이터 | dev 서버, 제출 직전 생성 |

> **덤프 파일은 이 저장소에 커밋하지 않습니다.** GitHub 저장소가 공개이고 GitLab은 그 미러라, 커밋하면 계정 이메일과
> 비밀번호 해시가 그대로 공개됩니다. 제출 채널에 파일로 따로 올립니다.

스키마만 필요하면 덤프 없이도 됩니다. backend가 기동할 때 Flyway가
`backend/src/main/resources/db/migration`(SQL)과 `backend/src/main/java/db/migration`(Java), V1~V52를 차례로 적용해 빈 DB를 최신 스키마로 만듭니다.
테이블 구조 설명은 [docs/design/03-erd.md](../docs/design/03-erd.md)와 `docs/design/erd-core.png`·`erd-others.png`에 있습니다.

## 3-2. 덤프 만들기 (서버)

서버의 compose 프로젝트 이름은 `beez`, DB 컨테이너는 `beez-postgres-1`입니다. 계정은 서버 env 파일의 `DB_USERNAME`입니다.

```bash
# 서버에서 실행. 비밀번호는 컨테이너 안 로컬 접속이라 묻지 않습니다
U=$(grep -E '^DB_USERNAME=' /opt/beez/.env.dev | cut -d= -f2-)
docker exec beez-postgres-1 pg_dump -U "$U" -d beez --no-owner --no-privileges \
  > ~/beez-dump-$(date +%Y%m%d).sql
ls -lh ~/beez-dump-*.sql
```

- `--no-owner --no-privileges`: 다른 계정 이름으로 복원해도 권한 오류가 나지 않게 소유자·권한 구문을 뺍니다.
- CDC용 논리 복제 슬롯·publication은 덤프에 포함되지 않거나 복원 환경에서 쓰지 않습니다. 복원할 때 관련 오류는 무시해도 됩니다.
- 파일을 PC로 가져올 때는 `scp <서버>:~/beez-dump-YYYYMMDD.sql .`를 씁니다.

## 3-3. 복원하기 (로컬 전체 스택)

```bash
# 1) 빈 DB로 스택을 띄웁니다. 이전 볼륨이 있으면 -v 로 지운 뒤 띄웁니다
docker compose -p beez down -v
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait postgres

# 2) 덤프를 넣습니다 (로컬 DB 계정은 infra/local.env 의 DB_USERNAME)
docker exec -i beez-postgres-1 psql -U beez -d beez < beez-dump.sql

# 3) 나머지 서비스를 띄웁니다. Flyway는 이미 적용된 버전을 건너뜁니다
docker compose -f infra/compose.yaml -f infra/compose.local.yaml --env-file infra/local.env up -d --wait
```

- 문서 **원본 파일**은 DB가 아니라 SeaweedFS에 있습니다. 덤프만 복원하면 문서 목록·본문 색인은 보이지만 원본 다운로드는
  실패합니다. 원본까지 옮기려면 [docs/infra/seaweedfs.md](../docs/infra/seaweedfs.md)의 백업·복원 절차를 따릅니다.
- 덤프 안의 계정 비밀번호는 해시로만 저장돼 있습니다. 시연 계정 정보는 제출 채널로 따로 전달합니다.
