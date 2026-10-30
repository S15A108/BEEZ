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
- **키 값을 바꾼 뒤에는 `--force-recreate`가 필요하다.** compose는 configs 내용만 바뀐 것을 변경으로 보지 않아 컨테이너를 그대로 둔다(2026-10-30 실측: 새 값 403 → 재생성 뒤 200). 원본은 volume에 있어 재생성해도 남는다.
- 파일이 깨지면 SeaweedFS가 기동하지 않고(healthcheck 실패 → 배포 `--wait` 실패), 신원이 0개면 모든 요청을 거부한다. 어느 쪽도 인증 없이 열리지 않는다(실측).
- `-master.telemetry=false`: 기본값은 저장량 10 GiB부터 하루 한 번 외부(telemetry.seaweedfs.com)로 통계를 보낸다.

### 내부망 분리 — S3 키만으로는 막히지 않는다

SeaweedFS mini는 S3(8333) 외에 filer(8888)·master(9333)·volume 포트를 같은 컨테이너에서 연다. **filer HTTP는 인증 없이 객체를 읽고 쓰고 지울 수 있다**(2026-10-30 로컬·서버 실측: S3 익명 요청은 403, filer 목록은 200). 그래서 seaweedfs를 `storage` 네트워크에만 두고 backend만 같이 붙인다. ai·postgres는 seaweedfs에 닿지 않는다. `storage`는 `internal: true`라 seaweedfs는 바깥으로도 나가지 못한다.

backend는 같은 network의 `http://seaweedfs:8333`으로 접근한다. 컨테이너 자신의 localhost를 다른 컨테이너 주소로 사용하지 않는다. 키는 Git/로그에 넣지 않는다.

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

- 문서 버전(`document_version`)과 제출본의 원본은 지우지 않는다(`DocumentOriginalCleanupWorker`는 참조가 남은 key를 지우지 않는다).
- 복사는 `rclone copy`로 한다. 원본에서 지워진 객체를 백업에서 지우지 않고, 실행마다 새 객체만 더한다.

**예외 — 작업본 원본.** `document_working_copy.storage_key`는 편집·제출 때 새 key로 바뀌고, 옛 key는 정리 워커가 몇 초 안에 지운다. 덤프 뒤·복사 전에 편집이 일어나면 그 작업본 원본은 백업에 없을 수 있다. 작업본 본문은 DB(`content_text`)에 있으므로 복원 시험은 경고만 한다. 문서 버전 원본이 빠지면 실패로 본다.

### 스크립트

| 파일 | 하는 일 |
| --- | --- |
| `infra/backup/backup.sh <환경>` | 서버에서 실행. `pg_dump -Fc` → 목차 검사 → 백업 키로 `rclone copy --immutable` → 덤프 시점까지의 문서 버전 원본이 백업에 다 있는지 확인. 덤프는 최근 14개만, 원본은 지우지 않는다 |
| `infra/backup/restore-check.sh <백업 폴더>` | Docker만 있으면 어디서나(서버·PC Git Bash). 임시 postgres·SeaweedFS에 복원하고 `storage_key`로 내려받아 `content_hash`와 대조. 운영 컨테이너는 건드리지 않는다 |

백업 위치는 서버의 `/var/backups/beez`(`db/`, `objects/<bucket>/`)다. 백업 키는 읽기·목록만 되므로 백업 스크립트가 잘못 돌아도 원본을 지우거나 바꿀 수 없다.

### 서버 밖 보관

서버 디스크가 사라지면 서버 안 백업도 함께 사라진다. 백업 폴더를 서버 밖으로 옮긴다. 별도 저장소가 정해지기 전까지는 PC에서 내려받는다.

```bash
# Git Bash (PC). 누적: 이미 받은 파일을 지우지 않는다
mkdir -p ~/beez-backup && ssh beez 'tar -C /var/backups/beez -cf - db objects' | tar -C ~/beez-backup -xf -
infra/backup/restore-check.sh ~/beez-backup
```

### 복원

- **원본 몇 개만 없어졌을 때**: 백업 폴더에서 bucket으로 bootstrap 키로 `rclone copy --immutable`한다. 이미 있는 객체는 건너뛰고 빠진 것만 올라간다(로컬 실측: 지운 원본 1개만 올라가고 API 다운로드 해시 일치).
- **전체 복원**(서버 재구축): 덤프를 `pg_restore`하고 원본 폴더 전체를 같은 방식으로 올린 뒤 `restore-check.sh`와 같은 대조를 운영 DB에 한다. 절차 전체는 -74 복구 런북에 둔다.

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

문서 HTTP 통합 시험은 실제 PostgreSQL과 SeaweedFS를 함께 사용해 업로드/다운로드·DB rollback 시 객체 삭제·현재 승인 범위 검색을 검증한다. DB 등록 서비스만 독립 검증하는 일부 시험은 src/test 전용 메모리 대역을 사용하며 제품에 등록하지 않는다.

- [SeaweedFS 공식 README](https://github.com/seaweedfs/seaweedfs): Docker/mini·S3_BUCKET·영속 경로.
- [SeaweedFS S3 인증](https://github.com/seaweedfs/seaweedfs/blob/master/weed/command/s3.go): identity/credentials·bucket actions.
- [Compose configs](https://docs.docker.com/reference/compose-file/configs/): `content`와 변수 치환.
- [rclone copy](https://rclone.org/commands/rclone_copy/): 대상에만 있는 파일을 지우지 않음, `--immutable`.
- [AWS SDK endpoint 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html): 명시 endpoint와 path-style.
