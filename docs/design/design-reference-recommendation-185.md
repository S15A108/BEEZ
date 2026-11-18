# S15P31A108-185 설계 근거 추천 내부 계약

> **BE–AI 내부 계약 구현 및 연동 제안.** 공개 CTX-A05 변경이나 #131·#223·BE·FE 연동 완료를 뜻하지 않는다. 실제 DB·화면에 연결하기 전에 담당자와 필드 및 책임을 확정한다.

## 역할과 검색 경계

- BE는 호출자 권한과 현재 baseline을 확인하고, #129의 문서 검색 및 명시적 문서 버전 등 관계로 **승인 설계 후보**를 구성한다. 같은 문서 버전이라는 사실은 후보 연결 기준일 수 있지만, 검색 청크가 해당 설계의 직접 근거라는 의미는 아니다.
- BE는 검색된 원문이 없는 경우, 원문이 있으나 연결된 설계가 없는 경우, 연결 여부를 확인하지 못한 경우를 구분해 전달한다. `mapping_complete=true`는 **조회된 검색 결과 전체에 대한 연결 확인을 마쳤다**는 의미이며, 검색된 모든 문서가 반드시 설계에 연결됐다는 뜻이나 미조회 범위가 없다는 뜻은 아니다. 연결이 확인된 설계가 없는 문서는 `unlinked_documents=true`, 확인 자체를 끝내지 못한 경우는 `mapping_complete=false`로 표현한다. 검색이 잘렸으면 별도로 `search_truncated=true`다.
- BE는 AI 응답을 공개하기 전에 권한·baseline과 입력 버전의 최신성을 다시 확인한다. 권한 실패·기준 변경·검색 장애는 부분 성공이나 정상 0건으로 바꾸지 않는다. 현재 AI는 BE의 DB와 원문을 직접 확인하지 않으므로 이 보장은 BE 연동의 책임이다.
- AI는 전달된 승인 설계 내용에서 추천 후보와 이유를 생성한다. 승인 여부·권한·문서 위치의 실제 일치 여부는 확인하지 않는다. AI가 고른 ID가 BE가 보낸 분석 가능 후보 안에 있는지 검사하며, 추천 결과는 확정된 관계가 아니다.
- #131과 권한, baseline 스냅샷·최종 재확인, 검색 어댑터 등의 재사용 가능성을 확인한다. 후보 선택·출력 및 부분 결과 정책은 기능별로 정한다. #131의 완전 검색 정책을 #185에 자동으로 적용하지 않는다. #132 컨텍스트 패킷과 추천 결과는 별도 출력이다.

## 내부 API

`POST /v1/design-reference-recommendations`는 내부 인증이 필요하다. 추천 모델 호출은 설정 `design_recommendation_enabled=false`가 기본이며, 활성화 및 `llm_usage.feature` 저장은 BE와 별도 연동이 필요하다.

### 요청 예시

```json
{
  "project_id": "101",
  "request_key": "recommendation-101-1",
  "call_key": "llm-recommendation-101-1-call-1",
  "request": "로그인 화면을 구현하고 싶어요.",
  "task_id": "501",
  "search_status": "READY",
  "search_observation": "DOCUMENTS_FOUND",
  "search_truncated": true,
  "mapping_complete": true,
  "unlinked_documents": false,
  "omissions": {
    "identified_candidates": [
      {"design_item_version_id": "312", "key": "API-007", "reason": "OTHER"}
    ],
    "unexamined_scope": true,
    "unexamined_count": null
  },
  "execution_budget_ms": 15000,
  "candidates": [
    {
      "design_item_version": {
        "id": "311", "design_item_id": "301", "key": "API-001",
        "kind": "API_CONTRACT", "version_no": 1, "status": "APPROVED",
        "schema_version": 1,
        "content": {"method": "POST", "path": "/api/auth/login"},
        "source": {
          "document_id": "201", "document_version_id": "211",
          "version_no": 1, "location": "저장된 위치"
        }
      },
      "design_source_verification": "UNVERIFIED",
      "search_evidence": [
        {
          "source": {
            "document_id": "201", "document_version_id": "212",
            "version_no": 2, "location": "검색된 위치"
          },
          "verification": "VERIFIED",
          "association": "EXPLICIT_LINK"
        }
      ]
    }
  ]
}
```

`source`는 **설계 항목에 저장된 출처**다. `search_evidence`는 이번 검색에서 찾은 원문과 BE가 판단한 후보 연결을 별도 보존한다. 위 예에서 저장 출처와 검색 결과는 다른 버전이다. BE는 현재 baseline과의 관계를 확인해야 하며, 과거 출처를 현재 검색 근거로 옮겨 적어서는 안 된다. 예시는 두 출처의 구조적 분리를 설명하며, 예시 자체가 둘 사이의 유효한 연결을 증명하지 않는다.

- `design_source_verification`과 각 `search_evidence.verification`은 BE가 전달하는 `VERIFIED` 또는 `UNVERIFIED`이며 생략 시 `UNVERIFIED`다. `VERIFIED`는 위치 문자열이 비어 있으면 거부한다. 그 외 원문 일치 여부·현재 baseline 소속은 BE가 확인해야 한다. AI가 검증했다는 뜻이 아니다.
- `association`은 `SAME_DOCUMENT_VERSION` 또는 `EXPLICIT_LINK`다. `SAME_DOCUMENT_VERSION`은 저장 출처의 문서 ID·버전 ID가 검색 출처와 일치할 때만 허용한다. `EXPLICIT_LINK`도 BE가 보고한 연결이며, 둘 다 **검색 청크가 설계의 직접 근거라는 AI 판정이 아니다.** 검색 원문의 실제 위치·다중 span 복원과 검증은 #223 및 BE와 연동해 확인한다.
- `search_observation`은 `NO_DOCUMENTS`, `DOCUMENTS_FOUND`, `UNKNOWN`이다. 이전 호출자는 생략 가능하지만 이 경우 `UNKNOWN`으로 처리해 검색 0건을 확정하지 않는다. `unlinked_documents=true`는 검색 문서 중 설계에 연결된 항목이 없는 문서가 있음을 뜻한다. `DOCUMENTS_FOUND`인데 매핑 완료·무연결 없음·후보 없음·알려진 생략과 미조회 범위가 모두 없다면 구성 모순이므로 `422 invalid_ai_input`이다.
- `omissions.identified_candidates`에는 **알고 있는** 생략 후보의 버전 ID와 `CONTENT_TOO_LARGE`, `CANDIDATE_LIMIT`, `OTHER` 사유를 넣는다. `key`는 BE가 알면 전달하는 선택 필드다. AI가 전달받은 후보를 크기·개수 한도로 생략하면 입력의 `design_item_version.key`를 사용한다. BE가 먼저 생략하면서 키를 전달하지 않았으면 AI는 키를 만들지 않고 생략한다. `unexamined_scope=true`는 확인하지 못한 범위가 있다는 뜻이다. `unexamined_count`는 식별된 생략 후보와 별개로 **모든 미조회 범위에 걸친 중복 없는 미조회 설계 후보의 총수**를 BE가 확실히 아는 경우에만 전달한다. 검색이 잘려 전체 범위를 보장할 수 없는 현재 계약에서는 BE가 일부 범위의 수를 보내더라도 응답은 `null`이다. 검색 잘림이 없어도 총수를 모르면 `null`로 보낸다. 미조회 후보 ID·키나 총개수를 추측하지 않는다.
- 각 입력 후보는 현재 baseline에 속한 승인 설계여야 한다. `source`가 없거나 위치가 미확인이어도 **설계 항목 내용 자체로 추천할 수 있다.** 위치는 추측하지 않는다.
- `kind`는 `REQUIREMENT`, `API_CONTRACT`, `DATA_CONTRACT`, `POLICY`, `CONSTRAINT` 중 하나다. `request`, ID, `execution_budget_ms` 등 나머지 필드와 오류·사용량 봉투는 기존 내부 API 공통 규칙을 따른다. 예산은 1~60,000ms이며 BE HTTP 제한보다 짧게 설정한다.

### 응답 예시

```json
{
  "result": {
    "status": "INSUFFICIENT",
    "candidates": [{
      "design_item_version": {
        "id": "311", "design_item_id": "301", "key": "API-001",
        "kind": "API_CONTRACT", "version_no": 1, "status": "APPROVED",
        "schema_version": 1,
        "content": {"method": "POST", "path": "/api/auth/login"},
        "source": {"document_id": "201", "document_version_id": "211",
                   "version_no": 1, "location": "저장된 위치"}
      },
      "reason": "로그인 기능에 필요한 API 계약입니다.",
      "sources": [],
      "design_source_status": "UNVERIFIED",
      "design_source": {
        "source": {"document_id": "201", "document_version_id": "211",
                   "version_no": 1, "location": "저장된 위치"},
        "verification": "UNVERIFIED"
      },
      "search_evidence": [{
        "source": {"document_id": "201", "document_version_id": "212",
                   "version_no": 2, "location": "검색된 위치"},
        "verification": "VERIFIED", "association": "EXPLICIT_LINK"
      }]
    }],
    "follow_up": "검색 범위와 분석하지 못한 항목을 확인해 주세요.",
    "incomplete_reasons": ["SEARCH_TRUNCATED", "CANDIDATES_OMITTED", "UNEXAMINED_SCOPE"],
    "omissions": {"identified_candidates": [
                    {"design_item_version_id": "312", "key": "API-007", "reason": "OTHER"}
                  ], "unexamined_scope": true,
                  "unexamined_count": null},
    "search_observation": "DOCUMENTS_FOUND",
    "unlinked_documents": false
  },
  "origin": "MODEL", "model": "configured-model", "usage": []
}
```

응답의 `sources`는 기존 후보 형태를 유지하기 위한 필드이며 **BE가 `VERIFIED`로 전달한 설계 저장 출처만** 담는다. 미확인 출처는 `design_source`에 원래 값과 상태를 보존하되 확인된 인용 목록에 넣지 않는다. `design_source_status`는 `VERIFIED`, `UNVERIFIED`, `ABSENT` 중 하나이며 문서 출처가 없으면 `ABSENT`, `design_source=null`, `sources=[]`다. 검색 근거는 `search_evidence`에만 있으며 자동으로 설계의 직접 인용에 넣지 않는다. 실제 usage는 공통 LLM 클라이언트가 전달한다. 예시의 `usage=[]`는 형태만 보여주며 모델 호출 시 사용량이 없다고 보장하지 않는다.

## 결과·누락 처리

| 조건 | 내부 결과 |
| --- | --- |
| 검색 완료·원문 0건·후보 0건·누락 없음 | 모델 호출 없이 `EMPTY` |
| 검색 원문은 있으나 연결 설계가 없거나, 매핑을 확인하지 못함 | `INSUFFICIENT`. `unlinked_documents` 또는 `MAPPING_INCOMPLETE`로 구분 |
| 검색 잘림·미조회·식별된 생략 후보 | 분석 가능한 후보를 반환하되 `INSUFFICIENT`와 사유·누락 정보 포함 |
| 문서 출처 없는 현재 baseline 승인 설계 | 설계 내용으로 추천 가능. 문서 출처 없음 표시 |
| 모델이 추천할 후보를 찾지 못함 | `INSUFFICIENT`. 다른 불완전 사유가 없으면 `NO_RECOMMENDATIONS` 및 요청 조건을 변경하라는 `follow_up`을 표시하며 검색 원문 0건으로 바꾸지 않음 |
| 검색 준비 중·검색 오류 | BE가 AI를 호출하지 않고 검색 상태에 맞게 처리한다. 이 상태를 AI에 전달하면 `422 invalid_ai_input`; 공개 `search_not_ready`·`search_failed` 응답은 BE 계약에서 정한다 |
| 권한 실패·baseline 변경 | BE가 오류 또는 재검토로 처리. AI 응답만으로 성공을 확정하지 않음 |

한 요청에서 AI는 설계 본문이 UTF-8 JSON 기준 16,000바이트 이하인 후보를 **최대 20개** 분석한다. 이를 넘는 알려진 후보는 원문을 자르지 않고 각각 `CONTENT_TOO_LARGE` 또는 `CANDIDATE_LIMIT`으로 기록한다. 입력 자체는 최대 100개 후보, 후보 본문당 1MiB, 후보당 검색 근거 20개와 위치 최대 10,000자를 넘으면 계약 오류다. 이는 AI의 별도 안전 한도이며 BE가 허용하는 모든 입력과 일치한다는 보장은 없다. BE는 전송 전 초과 여부를 확인해 누락된 ID·사유나 개수 미상의 범위를 전달해야 한다. **전송 자체를 거절한 요청은 부분 결과가 아니다.** 최종 한도와 전송 초과 오류는 BE·FE와 확정한다.

현재 구현의 `incomplete_reasons`는 `SEARCH_TRUNCATED`, `MAPPING_INCOMPLETE`, `UNLINKED_DOCUMENTS`, `SEARCH_OBSERVATION_UNKNOWN`, `CANDIDATES_OMITTED`, `UNEXAMINED_SCOPE`, `NO_RECOMMENDATIONS`를 사용한다. `search_truncated=true`면 미조회 범위가 있으므로 `UNEXAMINED_SCOPE`도 포함한다. 사유는 실제 전달·처리된 사실만 기록하며 미조회 항목의 ID를 만들지 않는다. 요청 JSON의 타입·필수 값 오류는 400 `validation_failed`, 유효한 형식이지만 업무 구조가 모순되는 입력은 422 `invalid_ai_input`이다.

## 남은 합의·연결

1. **BE·검색:** 실제 baseline에서 문서와 설계를 연결하는 조회 기준, 검색된 문서의 무연결·미확인 표시, 권한과 최종 최신성 재확인. 현재 AI 구현만으로는 검증할 수 없다.
2. **#223·BE:** 다중 span·긴 행 원문 위치의 저장·복원 및 `VERIFIED`를 부여하는 검증 주체와 기준. 검색 근거는 직접 인용으로 자동 승격하지 않는다.
3. **BE·FE:** 기존 공개 CTX-A05와 #151 화면에서 부분 결과·알려진 생략 후보·미조회 범위·문서 출처 없음을 어떻게 표시할지. 공개 API 변경은 이 PR에 포함되지 않는다.
4. **BE·운영:** 추천 사용량 feature의 DB 제약·저장 책임과 실제 모델 활성화·호출 예산. DB 사용량 저장은 이 PR에 포함되지 않는다.
5. **검증:** 실제 #131/#223 검색·원문, BE 권한/기준 변경, AI 호출, DB 저장과 화면까지 연결한 테스트 및 독립적인 추천 품질 평가.

현재 모의 공급자 테스트는 위 내부 구조·상태와 거부 조건을 확인하지만, BE의 권한 검증이나 실제 출처·검색 정확성을 증명하지 않는다.
