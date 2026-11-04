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

## 인프라 전달용 예시 — 실제 서버에는 아직 적용하지 않음

```yaml
services:
  seaweedfs:
    image: chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8
    environment:
      AWS_ACCESS_KEY_ID: ${SEAWEEDFS_BOOTSTRAP_ACCESS_KEY:?required}
      AWS_SECRET_ACCESS_KEY: ${SEAWEEDFS_BOOTSTRAP_SECRET_KEY:?required}
      S3_BUCKET: beez-documents
    volumes:
      - seaweedfs-data:/data
    ports:
      - "127.0.0.1:8333:8333"
    restart: unless-stopped

volumes:
  seaweedfs-data:
```

같은 Docker network의 backend는 seaweedfs:8333으로 접근하므로 호스트 포트 공개가 필수는 아니다. 컨테이너 자신의 localhost를 다른 컨테이너 주소로 사용하지 않는다. bootstrap 키는 초기 관리용이고 운영 앱에는 bucket 읽기/쓰기/삭제 범위의 별도 키를 제공한다. 키는 Git/로그에 넣지 않는다.

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

원본 백업은 DB 백업과 함께 복원 가능한 구성을 인프라 담당자가 정한다. MVP에서는 쓰기를 일시 중지하고 진행 중 요청 종료 후 PostgreSQL과 SeaweedFS 데이터 경로를 백업하는 방식도 가능하다. 복원 검증은 DB의 storage_key로 원본을 다운로드하고 원본 bytes의 SHA-256이 content_hash와 일치하는지 확인한다. 볼륨 재배포 보존 시험과 별도 백업 복원 시험을 구분한다.

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

문서 HTTP 통합 시험은 실제 PostgreSQL과 SeaweedFS를 함께 사용해 업로드/다운로드·DB rollback 시 객체 삭제·현재 승인 범위 검색을 검증한다. DB 등록 서비스만 독립 검증하는 일부 시험은 src/test 전용 메모리 대역을 사용하며 제품에 등록하지 않는다.

- [SeaweedFS 공식 README](https://github.com/seaweedfs/seaweedfs): Docker/mini·S3_BUCKET·영속 경로.
- [SeaweedFS S3 인증](https://github.com/seaweedfs/seaweedfs/blob/master/weed/command/s3.go): identity/credentials·bucket actions.
- [AWS SDK endpoint 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html): 명시 endpoint와 path-style.
