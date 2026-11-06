# 회의 결정 → 문서 수정 요청 변환 (S15P31A108-138)

상태: 담당자 합의 전 제안. `prepare_meeting_revision`은 내부 함수이며 HTTP 경로,
권한 검사, 결정 확정, 저장·승인·발행을 제공하지 않는다. 호출자는 같은 프로젝트의
회의·문서 권한, 확인된 결정 및 근거 관계, 현재 승인 버전을 확인하고 사용 직전
다시 확인한다. 입력 내 ID 일치 검사는 실제 DB 소속이나 확인 여부를 증명하지 않는다.

## 입력과 변환

- `project_id`, `meeting_id`, `document_id`, `document_version_id`: 서버가 확인한 식별자.
- `source`: 137의 SourceInput 전체. 문서 버전·프로젝트가 위 값과 일치하고, 정규화된
  원문과 text_hash·파서 프로필이 유효해야 한다. document_id와 버전의 실제 관계는 BE 책임이다.
- `entries`: 같은 회의의 `{id, meeting_id}` 목록. `decisions`는 확인된
  `{id, text, evidence_entry_ids}` 목록으로, 근거는 해당 입력의 발언 ID여야 한다.
  결정·발언 ID와 결정 문구의 중복은 거절한다. 결정 확정 여부는 입력 제공자가 보장한다.
- `selected_block_ids`: 같은 스냅샷의 파서 블록 ID 1~30개. 빈 목록, 중복 또는 다른
  스냅샷의 ID는 거절한다. 검색 청크나 설계 항목 ID를 대신 사용하지 않는다.
- `instruction`, 선택 `related_proposals`, `request_key`: 137 요청과 같은 의미.
  프로젝트별 권한과 제안의 관련성 확인은 호출자 책임이다.

아래는 형태 예시이며 `source`의 필수 필드는 137 계약을 따른다.

```json
{
  "project_id": "1", "meeting_id": "3", "document_id": "4",
  "document_version_id": "2", "source": {"...": "137 SourceInput 전체"},
  "entries": [{"id": "5", "meeting_id": "3"}],
  "decisions": [{"id": "6", "text": "팀장만 삭제", "evidence_entry_ids": ["5"]}],
  "selected_block_ids": ["해당 스냅샷의 블록 ID"],
  "instruction": "삭제 권한 수정", "related_proposals": [], "request_key": "req-138"
}
```

반환값의 `meeting`은 프로젝트·회의·문서·버전·발언·결정 관계를 보존한다.
`request`는 `source`, `expected_document_version_id`, `selected_block_ids`,
`instruction`, `agreements`(결정의 text 순서 그대로), `related_proposals`,
`request_key`를 담는다. 191 `connect_meeting_revision`에는 각각 `meeting`과
`request`를 `context`와 `prepared_request`로 전달하는 방안을 제안한다.
근거 텍스트의 유사도로 발언 ID를 추론하거나 변경 항목에 자동 연결하지 않는다.

## 오류와 결과

변환 중 검증 실패는 `ValueError` 계열(Pydantic ValidationError 포함) 또는
`SourceParsingError`다. 예: 다른 회의 발언·없는 근거 ID·버전 불일치·빈 선택·
잘못된 원문 해시는 호출하지 않고 실패한다. HTTP 상태와 오류 본문은 아직 합의된
공개 계약이 없으며 호출자가 정한다. 191·137에서 발생한 모델 오류와 usage,
변경 없음 및 unresolved는 변환부가 성공 초안으로 바꾸지 않는다.

## 검증과 남은 합의

변환 테스트는 다른 회의·없는 근거·중복 결정·다른 프로젝트·다른 버전·범위 오류를
검사한다. 191 `902fee4c`와 137 `28f07e24`의 실제 함수를 격리된 검사 사본에서
가짜 모델로 연결해 선택 문단만 바뀌고 결정→발언 링크가 유지됨을 확인했다.
이는 실제 모델, 회의 DB, 권한·현재성, 저장 또는 사용자 화면 검증이 아니다.

양지훈 님과 최종 필드·오류·호출 책임 및 MTG-A11/A13의 기존 회의 정리 흐름과
공통 모듈 재사용의 관계를 합의해야 한다. 두 선행 PR은 미병합이므로 이 문서와
함수는 합의 완료나 운영 연동 완료를 주장하지 않는다.
