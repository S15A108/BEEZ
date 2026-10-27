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
2. backend의 DOCUMENT_S3_* 환경변수 설정.
3. 새 backend 배포와 Flyway 적용, 기동 확인.
4. 새 문서 업로드/다운로드, 백엔드·SeaweedFS 재배포 후 같은 원본 다운로드 확인.

단일 서버 volume은 재배포 보존 수단이며 서버/디스크 장애 대비 백업이나 복제를 대체하지 않는다. 기존 원본 이전은 현재 필요 없다. 앞으로 롤백하더라도 객체가 남은 volume과 DB의 storage_key 참조는 보존한다.

## 검증·근거

단일 저장소 전환 후 전체 백엔드 검증: 단위 19개·통합 47개 통과. 문서 HTTP 통합은 실제 PostgreSQL과 SeaweedFS를 함께 사용한다. 로컬 Compose config 검증도 통과했으며 실제 배포 서버는 변경하지 않았다.

문서 HTTP 통합 시험은 실제 PostgreSQL과 SeaweedFS를 함께 사용해 업로드/다운로드·DB rollback 시 객체 삭제·현재 승인 범위 검색을 검증한다. DB 등록 서비스만 독립 검증하는 일부 시험은 src/test 전용 메모리 대역을 사용하며 제품에 등록하지 않는다.

- [SeaweedFS 공식 README](https://github.com/seaweedfs/seaweedfs): Docker/mini·S3_BUCKET·영속 경로.
- [SeaweedFS S3 인증](https://github.com/seaweedfs/seaweedfs/blob/master/weed/command/s3.go): identity/credentials·bucket actions.
- [AWS SDK endpoint 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html): 명시 endpoint와 path-style.
