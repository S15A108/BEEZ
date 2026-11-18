# #225 문서 CDC 로컬 실험

이 폴더는 운영 DB나 기존 발행 Outbox POC를 사용하지 않는 독립 실험이다.
별도 PostgreSQL 17·Debezium·Kafka에서 문서와 문서 버전의 최소 메타데이터를
초기 스냅샷 및 이후 WAL에서 캡처한다. 기존 색인 워커는 호출하지 않는다.

```text
test PostgreSQL: document, document_version
  → 제한된 PostgreSQL publication → Debezium 초기 snapshot + WAL
  → test Kafka의 각각 별도 토픽
```

프로젝트의 전체 스키마를 복제한 것은 아니다. `document`는 `id`·`project_id`,
`document_version`은 버전 ID·문서 ID·버전 번호·상태·파싱 상태·lock version·수정 시각만
발행한다. 문서 본문·저장소 경로·content hash·작성자·제목은 publication과
Debezium 캡처 allowlist에서 제외한다. 초기 snapshot에도 적용하도록 별도 복제
계정의 SELECT 권한을 허용 컬럼에만 부여한다. 스모크 테스트가 Kafka key·schema·value를
확인한다. 테스트 계정/비밀번호와 네트워크는 로컬 실험용이며 운영 보안을 뜻하지 않는다.

## 실행

Docker Desktop, Python 3, 로컬 포트 18084가 필요하다. 이 디렉터리에서:

```powershell
python verify_capture.py
docker compose -f compose.yaml ps
```

스크립트는 독립된 `beez-document-cdc-poc` 프로젝트를 시작하고 실험용 식별자로
커넥터·복제 슬롯·토픽을 만든다. 각 실행은 초기 snapshot 전에 행을 만들고 이후
커밋·롤백·동일 버전 수정·승인 상태 변경·parse 상태 갱신을 수행한다. Connect를
중지한 동안 DB 변경을 커밋하고, 같은 커넥터·오프셋·슬롯으로 재시작한 뒤 해당 변경이
전달되는지 확인한다. 완료 후 스크립트가
자신이 만든 커넥터와 슬롯을 제거한다. Kafka 토픽과 실험 데이터는 볼륨에 남는다.
테스트가 실패하거나 중단되면 출력의 경고를 확인한다.

데이터를 보존하며 전용 컨테이너만 중지하려면 이 폴더에서 다음을 실행한다.

```powershell
docker compose -f compose.yaml down
```

이 실험의 초기 snapshot은 사전에 존재하던 행을 `r`로, 그 뒤 insert/update는
`c`/`u`로 보여 준다. 내용 수정은 동일 버전 행에도 생길 수 있다. 다른 한편 파싱
상태만 수정한 `u`는 새 내용이나 승인·발행을 뜻하지 않는다. 여기서는 raw CDC만
확인하며 변경 유형 분류나 비즈니스 소비자를 구현하지 않는다. `document_version`에는
`project_id`가 없으므로 프로젝트는 원본 `document` 조회나 별도 계약이 필요하다.
`REPLICA IDENTITY DEFAULT`에서 삭제 `d`는 Kafka key와 `before`에 버전 PK는 포함하지만
`document_id`가 없어 삭제 시 문서·프로젝트를 식별할 수 없다. 이번 실험의 Debezium은
비어 있는 필수 정수 칸을 `0`으로 표현했으나, 이는 실제 문서 ID가 아니므로 신뢰하면 안 된다.
삭제를 처리하려면 이력
조회 또는 별도 안전한 식별 계약을 정해야 하며 운영 적용 방식은 미확정이다. `FULL`을
무심코 활성화하면 민감한 과거 컬럼이 노출될 수 있으므로 publication 및 role 권한과 함께
검토해야 한다.

실험은 snapshot 도중 동시 쓰기, 전체 프로젝트 실제 발행/색인 API, Kafka consumer
장애·offset 복구, 중복 제거와 프로젝트별 순서를 검증하지 않는다. Connect 재시작
후 전달은 정확히 한 번을 보장하지 않는다. 초기 적재·stream 경계, 중복/역순 방어,
접근 권한·암호화·보존 및 운영 배포는 담당자 계약과 추가 검증이 필요하다.
