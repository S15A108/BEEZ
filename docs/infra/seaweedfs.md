# SeaweedFS 문서 원본 저장 — 로컬·배포 공통

2026-10-27. 로컬 개발도 Docker SeaweedFS를 사용한다. 로컬 파일 adapter·.document-data·DOCUMENT_STORAGE_ROOT·DOCUMENT_STORAGE_PROVIDER는 제거했다. 문서 기능은 아직 배포하지 않아 기존 파일 이전 절차는 없다.

## 로컬 개발

1. Docker Desktop을 실행하고 docker version의 Server 응답을 확인한다.
2. backend에서 gradlew.bat bootRun을 실행한다. 기본 local 프로필의 Spring Docker Compose가 PostgreSQL과 SeaweedFS를 함께 시작한다.
3. BE는 http://127.0.0.1:8333으로 S3 요청을 보낸다. 원본은 beez-document-originals named volume → SeaweedFS 컨테이너 /data에 저장된다.

local 프로필과 backend/compose.yaml의 개발용 bucket/키 기본값은 일치한다. 이 개발용 키는 운영에 사용하지 않는다. 기본값을 바꾸면 Compose와 백엔드 실행 환경에 같은 DOCUMENT_S3_* 값을 제공한다. Compose가 읽는 .env 파일이 Spring 실행 환경에 자동 로드되는 것은 아니다.

Docker volume은 저장소 소스 파일이 아니므로 .document-data Git 무시 규칙은 필요 없다. 컨테이너 교체와 volume 삭제는 다르며 원본을 유지하려면 volume을 보존해야 한다.

## BE 설정과 배포 시 기동 조건

DocumentStorage의 제품 구현은 S3DocumentStorage 하나다. AWS SDK는 S3 서명/HTTP 클라이언트이고 요청 대상은 명시한 SeaweedFS endpoint다. AWS 계정을 요구하지 않는다. bucket을 경로로 접근하는 path-style을 사용한다.

서버에서 이 값이 어디서 들어오는지(compose·서버 env 파일)와 다른 서비스 변수는 [환경변수와 비밀값](env.md)에 모아 두었다. 아래 표의 의미가 바뀌면 그 문서의 표도 함께 고친다.

| 변수 | 배포 시 설정 |
| --- | --- |
| DOCUMENT_S3_ENDPOINT | 같은 Docker network이면 http://seaweedfs:8333 등 내부 주소. 호스트 직접 실행이면 서버에서 접근 가능한 주소 |
| DOCUMENT_S3_REGION | 서명 region, 기본 us-east-1 |
| DOCUMENT_S3_BUCKET | 사전에 만든 비공개 bucket |
| DOCUMENT_S3_ACCESS_KEY / SECRET_KEY | backend 전용 bucket 권한 키, 실행 환경/Secret에서 주입 |

**prod 프로필에는 개발용 기본 endpoint/bucket/키가 없다. 필수 설정 누락은 기동 실패다.** local 파일 저장으로 대체하지 않는다. 설정은 유효하지만 SeaweedFS가 꺼져 있거나 키가 틀리면 앱은 기동할 수 있어도 실제 원본 요청은 실패한다. 그러므로 이번 버전 배포 전에 인프라 담당과 적용 순서를 맞춘다.

## 서버 구성 — 키 분리와 내부망 (S15P31A108-211)

서버는 `infra/compose.yaml`의 seaweedfs를 쓴다. 이미지는 위 4.51 digest 고정, 원본은 `document-originals` volume(`/data`), 호스트 포트는 열지 않는다.

### S3 신원 셋

`AWS_ACCESS_KEY_ID` 환경변수로 관리 키 하나만 만드는 방식은 쓰지 않는다. 그 키를 backend가 같이 쓰면 backend가 bucket 생성·삭제·정책 변경까지 할 수 있다. 대신 `-s3.config` 파일로 신원 셋을 준다. 파일은 compose `configs`의 `content`가 env 파일 값으로 채워 컨테이너 안에만 만든다(`docker inspect`에 값이 보이지 않는다).

| 신원 | env 변수 | 권한 | 쓰는 곳 |
| --- | --- | --- | --- |
| bootstrap | `SEAWEEDFS_BOOTSTRAP_ACCESS_KEY` / `_SECRET_KEY` | Admin(모든 bucket) | 운영자의 관리·복원 작업만. backend에 주지 않는다 |
| backend | `DOCUMENT_S3_ACCESS_KEY` / `_SECRET_KEY` | `beez-documents`의 Read·Write·List (Write에 삭제 포함) | backend 컨테이너 |
| backup | `SEAWEEDFS_BACKUP_ACCESS_KEY` / `_SECRET_KEY` | `beez-documents`의 Read·List | `infra/backup/backup.sh` |

- 값은 `openssl rand -hex`로 만든다. 16진수라 env 파일과 JSON 안에서 따옴표·`$` 문제가 없다.
- bucket은 기동할 때 `S3_BUCKET`으로 없으면 만든다. 공개 정책을 붙이지 않으며 backend 키로는 정책·ACL을 바꿀 수 없다.
- **compose는 configs 내용만 바뀐 것을 변경으로 보지 않는다**(2026-10-30·11-05 실측: 비밀값만 바꾸면 컨테이너가 그대로라 새 값은 403). 그래서 세 access key를 `S3_CONFIG_ACCESS_KEYS` 환경변수에도 넣어, access key가 바뀌면 배포가 seaweedfs를 다시 만들게 했다. access key는 비밀이 아닌 식별자라 `docker inspect`에 보여도 된다. **키를 바꿀 때는 access key와 secret key를 늘 함께 바꾼다.** 원본은 volume에 있어 재생성해도 남는다.
- 파일이 깨지면 S3가 뜨지 않아 healthcheck(`/healthz`)가 실패하고 배포 `--wait`가 실패한다. 신원이 0개면 모든 요청을 거부한다. 어느 쪽도 인증 없이 열리지 않는다(실측).
- `-master.telemetry=false`: 기본값은 저장량 10 GiB부터 하루 한 번 외부(telemetry.seaweedfs.com)로 통계를 보낸다.

### 내부망 분리 — S3 키만으로는 막히지 않는다

SeaweedFS mini는 S3(8333) 외에 filer(8888)·master(9333)·volume 포트를 같은 컨테이너에서 연다. **filer HTTP는 인증 없이 객체를 읽고 쓰고 지울 수 있다**(2026-10-30 로컬·서버 실측: S3 익명 요청은 403, filer 목록은 200). 그래서 seaweedfs를 `storage` 네트워크에만 두고 backend만 같이 붙인다. ai·postgres는 seaweedfs에 닿지 않는다. `storage`는 `internal: true`라 seaweedfs는 바깥으로도 나가지 못한다.

- 2026-11-05 서버 실측: 내부망 적용 전에는 ai 컨테이너에서 `http://seaweedfs:8888/`이 인증 없이 200이었다(그때 문서 0건).
- 남는 위험: backend는 storage에 붙어 있어 filer에 닿는다. backend가 뚫리면 bucket 범위와 무관하게 원본을 지울 수 있다. 백업이 삭제를 옮기지 않으므로 백업에서 되살린다.
- filer의 IAM gRPC도 인증이 없어 같은 망에서 신원을 만들 수 있다(로그 경고 "IAM gRPC service on filer is UNAUTHENTICATED"). 같은 이유로 storage 망만 믿는다.

backend는 같은 network의 `http://seaweedfs:8333`으로 접근한다. 컨테이너 자신의 localhost를 다른 컨테이너 주소로 사용하지 않는다. 키는 Git/로그에 넣지 않는다.

### 서버에 처음 적용하기 (이 구성이 들어간 PR을 머지하기 전)

compose가 새 키 넷을 필수(`:?`)로 읽으므로, **env 파일에 먼저 넣고 머지한다**([env.md 변수를 추가할 때 7](env.md#6-변수를-추가할-때)). 순서가 바뀌면 머지 뒤 자동 배포가 배포 전 점검에서 멈춘다(서비스는 이전 버전으로 계속 돈다).

1. 서버 env 파일에 bootstrap·backup 키를 새 난수로 더한다. 값은 화면에 나오지 않는다.

   ```bash
   # 서버(ssh beez)
   cd /opt/beez && f=.env.dev && \
   if grep -q '^SEAWEEDFS_' "$f"; then echo "이미 있음 - 멈춤"; else
     cp -p "$f" "$f.bak-211" && printf '%s\n' \
       "SEAWEEDFS_BOOTSTRAP_ACCESS_KEY=$(openssl rand -hex 12)" \
       "SEAWEEDFS_BOOTSTRAP_SECRET_KEY=$(openssl rand -hex 24)" \
       "SEAWEEDFS_BACKUP_ACCESS_KEY=$(openssl rand -hex 12)" \
       "SEAWEEDFS_BACKUP_SECRET_KEY=$(openssl rand -hex 24)" >> "$f"; fi; \
   grep -o '^[A-Z0-9_]*=' "$f" | tr '\n' ' '; echo
   ```

   - `DOCUMENT_S3_*`는 그대로 둔다. 지금까지 관리 키였던 그 값이 머지 뒤에는 `beez-documents` 범위의 backend 키가 된다. 관리 권한은 새 bootstrap 키로 옮겨 간다.
   - 지금 떠 있는 compose는 새 키를 읽지 않으므로 머지 전 배포에는 영향이 없다.
   - `.env.dev.bak-211`에도 비밀값이 있다. 확인이 끝나면 지운다.
2. 이 PR 브랜치로 [배포 전에 점검하기](env.md#배포-전에-점검하기)를 돌려 `env ok`를 본다.
3. 머지 → 자동 배포. seaweedfs·backend가 새 네트워크와 신원 파일로 다시 만들어진다. 원본은 volume에 있어 남는다.
4. 확인: `docker compose -p beez ps`에서 seaweedfs가 `(healthy)`인지 본다. ai에서 filer가 막혔는지(`docker exec beez-ai-1 python -c "import urllib.request as u; u.urlopen('http://seaweedfs:8888/', timeout=3)"`가 실패) 본다. 끝으로 화면에서 업로드·다운로드를 해 본다.

### 키 교체

키가 샜거나 사람이 바뀌어 바꿀 때 쓴다. 셋 다 같은 방법이다. 새 값을 env 파일에 넣고 **지금 떠 있는 SHA로 다시 배포**한다([운영 안내 env 값을 바꿨을 때](operations.md#env-값을-바꿨을-때)).

```bash
# 서버(ssh beez). K 에 바꿀 키 이름의 앞부분: DOCUMENT_S3 / SEAWEEDFS_BOOTSTRAP / SEAWEEDFS_BACKUP
K=DOCUMENT_S3; f=/opt/beez/.env.dev; cp -p "$f" "$f.bak-rotate" && \
sed -i -e "s/^${K}_ACCESS_KEY=.*/${K}_ACCESS_KEY=$(openssl rand -hex 12)/" \
       -e "s/^${K}_SECRET_KEY=.*/${K}_SECRET_KEY=$(openssl rand -hex 24)/" "$f" && \
grep -c "^${K}_\(ACCESS\|SECRET\)_KEY=[0-9a-f]\+$" "$f"   # 2 가 나와야 한다
ls -l "$f"                                                  # -rw-r----- ubuntu beez-deploy 그대로인지(Jenkins 가 그룹으로 읽는다)
```

- `.bak-rotate`에는 옛 값이 남는다. 배포가 성공하면 지우고, 실패하면 이 파일로 되돌린다.
- access key와 secret key를 함께 바꾼다. secret key만 바꾸면 seaweedfs가 다시 만들어지지 않아 backend가 새 secret으로 403을 받는다(위 S3 신원 셋).
- 다시 배포하면 seaweedfs와 그 키를 쓰는 backend가 함께 다시 만들어진다. 몇 초 동안 문서 업로드·다운로드가 실패할 수 있고, backend가 다시 만들어지면 웹 사용자가 로그아웃된다. 팀 채널에 먼저 알린다.
- 확인: 배포 성공 뒤 화면에서 문서 하나를 다운로드한다. 백업 키를 바꿨으면 `backup.sh`를 한 번 손으로 돌린다([운영 안내 8](operations.md#8-백업복원)).
- 2026-11-05 로컬 확인: backend access key를 바꿔 다시 띄우면 seaweedfs가 다시 만들어지고, 옛 키는 403, 새 키는 정상이다.

## 최초 배포 순서

1. SeaweedFS·영속 volume·인증·비공개 bucket·backend 전용 키 준비.
2. backend의 DOCUMENT_S3_* 환경변수 설정. CORS_ALLOWED_ORIGINS에도 실제 프론트 출처(예: https://dev.beez.ai.kr)를 포함한다. 경로·마지막 / 없이 scheme·host·port만 명시한다. 같은 출처 배포에서도 문서 변경 요청의 Origin 검증에 필요하다.
3. 새 backend 배포와 Flyway 적용, 기동 확인.
4. 실제 프론트 Origin을 포함한 업로드·초안·분류·분석 재시도를 확인하고, 백엔드·SeaweedFS 재배포 후 같은 원본 다운로드 확인.

단일 서버 volume은 재배포 보존 수단이며 서버/디스크 장애 대비 백업이나 복제를 대체하지 않는다. 기존 원본 이전은 현재 필요 없다. 앞으로 롤백하더라도 객체가 남은 volume과 DB의 storage_key 참조는 보존한다.

## 저장 원자성의 범위와 실패 처리

PostgreSQL과 SeaweedFS를 함께 커밋하는 분산 트랜잭션은 사용하지 않는다. 짧은 사전 검사 후 DB 트랜잭션·project 잠금 없이 원본을 PUT한다. 이후 짧은 트랜잭션에서 project를 FOR NO KEY UPDATE로 잠그고 권한·멱등성·종류 중복 또는 기준 승인본을 재검사한 뒤 문서·버전·분석 접수·멱등 응답을 함께 커밋한다. 업로드 성공 응답은 DB 커밋 후 반환된다. 변경 원문은 새 UUID key에 저장하며 기존 승인 원본을 덮어쓰지 않는다. 최초 등록과 수정 초안은 DocumentOriginalWriter의 동일한 보상 정책을 사용한다.

| 실패 상황 | 현재 처리 |
| --- | --- |
| 원본 PUT 실패 | 성공 응답 및 문서 DB 등록을 하지 않는다. 원격 쓰기 결과가 불확실할 수 있으므로 key와 예외 종류를 로그에 남긴다 |
| PUT 성공 후 DB rollback | afterCompletion으로 확정된 rollback을 확인하고 DB 자원을 반환한 뒤 새 원본을 삭제한다. 문서·버전·작업·멱등 응답도 함께 rollback된다 |
| PUT 성공 후 DB 트랜잭션 시작 실패 | DB 참조가 생성되지 않았으므로 새 원본을 보상 삭제한다 |
| 동시 멱등 요청의 기존 응답 재사용 | 이번 요청의 새 원본은 DB에서 참조하지 않으므로 커밋 후 삭제한다 |
| 보상 삭제 실패 | DB rollback을 유지하며 orphan_key를 로그에 남긴다 |
| DB commit 결과 불명 | 참조가 실제 커밋됐을 수 있으므로 원본을 보존하고 original_key를 로그에 남긴다 |
| PUT 이후 프로세스 강제 종료 | 보상 콜백이 실행되지 않아 미참조 객체가 남을 수 있다. 완전한 DB·객체 원자성은 보장하지 않는다 |

운영자는 실패 로그의 key를 DB `document_version.storage_key`와 대조한다. 업로드·원격 PUT이 아직 진행 중이거나 커밋 결과가 불명인 상태에서 객체를 삭제하지 않는다. 필요하면 업로드를 잠시 멈추고 진행 중 요청과 DB 상태를 확인한 뒤, 참조되지 않는 것이 확인된 객체만 수동 정리한다. 로그 전에 프로세스가 종료됐다면 bucket 목록과 DB 참조를 대조해야 한다. 자동 고아 정리·지연삭제 큐·스냅샷 기능은 이번 MVP에 도입하지 않는다.

원본 백업은 아래 "백업·복원"을 따른다. 복원 검증은 DB의 storage_key로 원본을 다운로드하고 원본 bytes의 SHA-256이 content_hash와 일치하는지 확인한다. 볼륨 재배포 보존 시험과 별도 백업 복원 시험을 구분한다.

## 백업·복원 (S15P31A108-211)

### 순서와 일관성

**pg_dump 먼저, 원본 복사 나중이다.** 덤프가 가리키는 원본이 덤프 뒤의 복사에 들어 있어야 한다. 쓰기를 멈추지 않아도 되는 근거는 두 가지다.

- 제출·승인된 문서 버전의 원본은 지우지 않는다(`DocumentOriginalCleanupWorker`는 참조가 남은 key를 지우지 않는다).
- 복사는 `rclone copy`로 한다. 원본에서 지워진 객체를 백업에서 지우지 않고, 실행마다 새 객체만 더한다.

**예외 — 덮어써지는 원본.** 아래 key는 새 key로 바뀌고, 옛 key는 정리 워커(-194)가 몇 초 안에 실제로 지운다. 덤프 뒤·복사 전 몇 초 사이에 이런 변경이 있으면 덤프가 가리키는 옛 원본이 백업에 없을 수 있다.

- `document_working_copy.storage_key`: 편집·제출할 때. 작업본 본문은 DB(`content_text`)에 있으므로 복원 시험은 경고만 한다.
- 처음 제출하는 문서의 초기 `document_version.storage_key`: 첫 제출 때 같은 행의 key를 바꾼다(`DocumentReviewRepository.submit`). 이 경우 그날 `backup.sh`가 실패로 끝나고 `LAST_OK`를 바꾸지 않는다(덤프가 가리키는 key를 덤프에서 꺼내 대조한다). 다음 날 백업에는 새 key가 들어가므로 그 덤프로 복원하면 된다.

새벽 4시 실행이고 틈이 몇 초라 드물다. 그래서 쓰기를 멈추는 대신 복원 시험으로 잡기로 했다.

### 스크립트

| 파일 | 하는 일 |
| --- | --- |
| `infra/backup/backup.sh <환경>` | 서버에서 매일 실행(crontab). `pg_dump -Fc` → 목차 검사 → 백업 키로 `rclone copy --immutable --checksum` → **덤프에서 꺼낸** 문서 버전 `storage_key`가 백업에 다 있는지 확인 → 성공하면 `LAST_OK`에 덤프 이름. 다른 백업·받기가 잠금을 쥐고 있으면 최대 30분 기다린다. 덤프는 최근 14개만, 원본은 지우지 않는다 |
| `infra/backup/pull-backup.sh [폴더] [--check]` | PC Git Bash. 서버 백업에서 PC에 없는 파일만 받는다. 지우지도 덮어쓰지도 않는다. 임시 폴더(`.incoming`)에 받아 끝까지 성공했을 때만 옮긴다. 서버 백업이 도는 중이면 끝날 때까지 기다린다. `--check`면 받은 뒤 `LAST_OK`의 덤프로 `restore-check.sh`까지 돌린다 |
| `infra/backup/restore-check.sh <백업 폴더>` | Docker만 있으면 어디서나(서버·PC Git Bash). 임시 postgres·SeaweedFS에 복원하고 `storage_key`로 내려받아 `content_hash`와 대조. 운영 컨테이너는 건드리지 않는다 |
| `infra/backup/restore.sh <환경> objects·all --yes` | 서버. 운영 복원. `objects`는 백업 원본을 bucket에 올리고(지우지 않음), `all`은 배포 잠금을 쥔 채 backend를 멈추고 **DB를 빈 DB로 다시 만들어** 덤프를 복원한 뒤 원본을 올리고 backend를 켠다. `--yes` 없이는 할 일만 보여 준다 |

서버의 백업 위치는 `/var/backups/beez`(`db/`, `objects/<bucket>/`)다. 백업 키는 읽기·목록만 되므로 백업 스크립트가 잘못 돌아도 원본을 지우거나 바꿀 수 없다.

**잠금 원칙 (2026-11-05 정함)** — 스크립트끼리 겹칠 때의 규칙은 이것 하나로 정한다. 스크립트를 더할 때도 이 표를 따른다.

| 스크립트 | `/var/backups/beez/.backup.lock` | `/var/www/beez/.deploy.lock` |
| --- | --- | --- |
| `backup.sh` | 잡는다(최대 30분 기다림) | 안 잡는다 |
| `restore.sh` | 잡는다(최대 30분 기다림) | `all`일 때만, 백업 잠금 **다음에** 잡는다 |
| `deploy.sh` | 안 잡는다 | 잡는다 |
| `pull-backup.sh`·`check-live.sh`·`restore-check.sh` | 안 잡는다(읽기만) | 안 잡는다 |

- 서버 백업 데이터를 만들거나 그것으로 운영을 바꾸는 작업은 백업 잠금 하나로 줄 세운다. 그래서 복원 중에 예약 백업이 반쯤 복원된 DB를 `LAST_OK`로 기록하는 일이 없다.
- 읽기만 하는 것은 잠금을 잡지 않는다. 받기가 느려도 백업을 막지 않는다. 대신 받기는 `LAST_OK`를 목록보다 먼저 떠 둔다(`LAST_OK`는 덤프·원본이 다 갖춰진 뒤에 쓰이고, 원본은 지우지 않는다). `LAST_OK`가 가리키는 덤프를 받지 못했으면 PC의 `LAST_OK`를 바꾸지 않는다.
- 두 잠금을 다 잡는 것은 `restore.sh all`뿐이고 순서는 늘 백업 → 배포다. 반대 순서로 기다리는 곳이 없어 서로 막히지 않는다.

- `--checksum`: 이미 받은 원본과 같은지를 수정 시각이 아니라 내용(MD5)으로 본다. 수정 시각으로 보면 1초 미만 차이를 "바뀜"으로 보고 `--immutable`이 두 번째 실행부터 매번 실패했다(2026-11-05 실측).
- `--immutable`: 백업에 이미 있는 원본의 내용이 다르면 덮어쓰지 않고 실패한다. 백업이 변조됐거나 깨진 것을 알린다(같은 크기로 내용만 바꾼 파일도 잡음, 실측).

### 서버 밖 보관 — PC가 당겨 온다

서버 디스크가 사라지면 서버 안 백업도 함께 사라진다. 그래서 **서버 밖 보관은 PC가 `pull-backup.sh`로 당겨 오는 방식**으로 정했다(2026-11-05 전진).

| 고른 이유 | 받아들인 위험 |
| --- | --- |
| 비용 0. 서버에 바깥 저장소 쓰기 키를 두지 않아 서버가 뚫려도 PC의 백업은 지울 수 없다 | PC가 꺼져 있거나 사람이 잊으면 서버 밖 사본이 오래된다. SSAFY PC를 바꾸거나 초기화하면 PC 사본이 사라진다 |

- 받는 쪽 기본 폴더는 `~/beez-backup/dev`다. 출근해서 한 번 돌린다. 마지막 **성공** 백업(`LAST_OK`)이 36시간보다 오래되면 종료 코드 1과 경고를 내므로 서버 백업이 멈추거나 매일 실패하는 것도 이때 안다.
- PC를 바꿀 때는 이 폴더를 새 PC로 옮긴다. 옮기지 못하면 새 PC에서 처음부터 받는다. 서버에는 덤프 14개와 원본 전부가 있다.
- 비교했던 다른 안: 개인 AWS S3(월 1센트 수준, 서버에 쓰기 키가 생김), 무료 R2·B2(새 계정, 팀 데이터가 제3자로 감).

### 복원

절차는 [운영 안내 8. 백업·복원](operations.md#8-백업복원)에 있다.

- **원본 몇 개만 없어졌을 때**: `restore.sh <환경> objects --yes`. 이미 있는 객체는 건너뛰고 빠진 것만 올라간다(로컬 실측: 지운 원본만 올라가고 다운로드 해시 일치).
- **전체 복원**(서버 재구축 포함): `restore.sh <환경> all --yes` 뒤 `check-live.sh`. `pg_restore --clean`으로 덮어쓰지 않고 빈 DB에 복원한다 — 덤프 뒤 마이그레이션의 테이블이 남으면 Flyway가 기동 중에 멈춘다.

## 분석 작업의 만료 복구

RUNNING lease는 10분이며, 만료 시 새 attempt로 자동 복구한다. automatic_recoveries는 최초 실행 이후 최대 2회(총 3회 실행)이고, 다시 만료되면 FAILED / lease_recovery_exhausted로 종료한다. 사람 팀장의 명시적 재시도는 복구 예산만 초기화하며 attempt는 증가시켜 이전 결과를 계속 차단한다. 일반 AI 호출 실패는 즉시 FAILED가 된다. 기본 AI 연결/읽기 제한은 3초/20초이며 lease보다 짧다. AI_READ_TIMEOUT을 늘릴 경우 두 호출과 처리 시간을 포함한 실행이 lease를 넘지 않도록 함께 검토한다.

## 배포 전 역할

- 오서현: 저장 실패·DB rollback·멱등 요청·기존 승인본 보존 검증, 실패 추적 로그와 문서 구현.
- 전진: 운영 SeaweedFS·영속 volume·비공개 bucket·backend 전용 키·내부 네트워크·환경변수 준비, DB와 원본 백업·복원 절차 확인.
- 공동: 인증 PR #22 반영 후 실제 서버에서 업로드·다운로드·재배포 보존을 확인. 저장소 준비 전 이 PR을 배포하지 않는다.

문서 변경 요청은 기존 CORS_ALLOWED_ORIGINS를 출처 검증에도 사용하므로 실제 프론트 origin을 정확히 설정한다. 로그인·공통 SecurityConfig는 인증 담당 구현을 사용하며 문서 PR에서 수정하지 않는다.

## 검증·근거

2026-10-28 리뷰 보완 검증: 현재 문서 브랜치의 단위 테스트 36개 통과. 현재 브랜치에는 공통 로그인 API가 아직 없어 문서 HTTP 테스트 17개가 준비 단계에서 실패하므로, PR #22(`4bd1b96`)를 합친 별도 임시 사본에서 최종 코드의 전체 단위 51개·통합 91개를 검증해 모두 통과했다. 문서 HTTP 통합 17개에는 PUT 중 project FK INSERT 진행, 동시 멱등 요청의 미사용 객체 정리, 기준 승인본 재검사, lease 자동 복구 상한, 다른 프로젝트·과거 버전의 더 가까운 청크 1,024개 속에서 허용 결과 12건의 순위·페이지 유지가 포함된다. FE는 lint·format 검사, 테스트 103개, TypeScript/Vite build를 통과했다. 검증을 위해 인증 구현을 현재 브랜치에 병합하거나 배포 서버를 변경하지 않았다.

인증 정렬·원자성 실패 추적 보완 후 전체 백엔드 검증: 단위 43개·통합 84개 통과. 문서 HTTP 통합 11개는 실제 PostgreSQL과 SeaweedFS를 함께 사용한다. 로컬 Compose config 검증도 통과했으며 실제 배포 서버는 변경하지 않았다.

2026-10-30 키 분리·백업 로컬 검증(S15P31A108-211): dev와 같은 이미지(`9f9af95`)로 별도 compose 프로젝트를 띄워 확인했다. backend 키는 자기 bucket의 PUT·GET·DELETE·목록만 되고 다른 bucket의 읽기·쓰기·삭제, bucket 생성·삭제, 공개 정책·ACL 변경은 403. backup 키는 GET·목록만 되고 PUT·DELETE 403. 익명 요청 403. 실제 API 업로드 3건 → `backup.sh` → `restore-check.sh` 3건 일치. 원본을 변조·삭제한 백업은 `restore-check.sh`가 실패(불일치 1·없음 1)로 잡고, bucket에서 지운 원본은 기존 백업에 남으며 새 백업 폴더에서는 `backup.sh`가 실패로 잡는다. 환경변수 관리 키 방식에서 config 방식으로 바꿔도 같은 volume의 원본이 남고, 옛 키는 관리 권한을 잃는다(bucket 생성 403).

2026-11-05 compose 반영 검증(S15P31A108-211, 로컬 Docker Desktop, compose 5.2.0, 별도 프로젝트 `beez211`):
- 새 키 하나라도 빠지면 `config`가 `... is required`로 멈춘다.
- seaweedfs는 6초 안에 `(healthy)`가 된다. `docker inspect`에 secret이 0건이다.
- 권한: backend 키는 bucket 생성·삭제 403, backup 키는 쓰기·삭제 403, bootstrap 키는 bucket 생성 가능, 익명 403이다.
- 망: filer는 default 망에서 닿지 않고(ai에서 이름 해석 실패), storage 망은 바깥으로 나가지 못한다.
- 키 교체: access key를 바꾸면 seaweedfs가 다시 만들어지고, 옛 키 403·새 키 정상이다. secret만 바꾸면 다시 만들어지지 않는다.
- 백업 흐름: `backup.sh` 두 번 연속 성공(`--checksum` 넣기 전에는 두 번째가 `immutable file modified`로 실패) → `pull-backup.sh` 증분·삭제 비전파·임시 파일 제외 → `--check`로 4건 일치. 백업 파일 내용을 같은 크기로 바꾸면 `backup.sh`가 실패로 잡는다.
- 복원: 원본 2개를 지운 bucket을 운영 안내 8절 명령으로 되살렸고(`check-live` 일치 2 → 4), 비운 DB에 `pg_restore --clean`한 뒤 4건 일치했다.
- dev 이미지(`e3f17c0`) backend·ai가 새 compose로 모두 `(healthy)`다.

2026-11-05 리뷰 반영 검증(같은 환경):
- 덤프에서 key 뽑기: storage_key가 셋째 열이고 다른 열에 탭이 든 행이 있어도 3건을 정확히 뽑는다.
- `LAST_OK`: 덤프가 없는 원본을 가리키면 `backup.sh`가 실패하고 `LAST_OK`는 그대로다. `pull-backup.sh --check`와 `restore-check.sh` 기본값은 이름순 최신(실패본)이 아니라 `LAST_OK`의 덤프로 통과한다.
- 받기: 3000바이트에서 끊긴 받기는 PC 폴더를 그대로 두고 임시 폴더를 버린다. 다시 받으면 정상이다. 오래된 `LAST_OK`·`LAST_OK` 없음은 종료 코드 1이다.
- 잠금(리눅스 컨테이너의 flock): 받기 중 백업은 기다렸다 진행하고, 백업 중 받기도 기다린다. 런북처럼 셸이 쥔 복원 잠금은 `deploy.sh`와 같은 방식의 잠금을 놓을 때까지 막는다.
- `check-live.sh`: DB 조회 실패는 종료 코드 2와 "DB 조회 실패", 0건은 통과다.
- seaweedfs를 멈춘 채 backend를 다시 만들어도 backend는 `(healthy)`다(`depends_on: service_started`).

2026-11-05 hyunneee 리뷰 반영:
- env 읽기: 스크립트가 env 파일을 sed가 아니라 `docker compose config --environment`(check-port.sh와 같은 방식)로 읽는다. 키를 `"…" # 주석` 형태로 쓴 env에서 compose 배포, backend 키 PUT, `backup.sh`, `check-live.sh`가 모두 같은 키로 동작했다.
- 문서 0건: 빈 bucket의 백업은 `objects/beez-documents`를 만들고 `LAST_OK`를 쓴다. PC 사본에는 빈 폴더가 없어도 `restore-check.sh`가 통과하고, `restore.sh all`은 "올릴 원본 0개"로 넘어가 backend가 healthy로 뜬다. 문서 1건을 넣은 뒤 백업·받기·복원 시험도 그대로 통과했다(실제 컨테이너).
- 시험 `infra/backup/test_backup_scripts.py`(9개, 가짜 docker·진짜 compose): 고치기 전 스크립트에서는 env 4건·빈 bucket 3건이 실패한다. CI `deploy-scripts` 잡에서 돈다.

문서 HTTP 통합 시험은 실제 PostgreSQL과 SeaweedFS를 함께 사용해 업로드/다운로드·DB rollback 시 객체 삭제·현재 승인 범위 검색을 검증한다. DB 등록 서비스만 독립 검증하는 일부 시험은 src/test 전용 메모리 대역을 사용하며 제품에 등록하지 않는다.

- [SeaweedFS 공식 README](https://github.com/seaweedfs/seaweedfs): Docker/mini·S3_BUCKET·영속 경로.
- [SeaweedFS S3 인증](https://github.com/seaweedfs/seaweedfs/blob/master/weed/command/s3.go): identity/credentials·bucket actions.
- [Compose configs](https://docs.docker.com/reference/compose-file/configs/): `content`와 변수 치환.
- [rclone copy](https://rclone.org/commands/rclone_copy/): 대상에만 있는 파일을 지우지 않음, `--immutable`.
- [AWS SDK endpoint 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html): 명시 endpoint와 path-style.
