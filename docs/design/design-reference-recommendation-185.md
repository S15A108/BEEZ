# S15P31A108-185 설계 근거 추천 내부 계약 초안

> **제안 및 부분 구현:** AI 내부 추천 기능의 시작 범위를 기록한다. 공개 API·BE/AI 연동·DB 저장·화면 계약의 합의를 뜻하지 않는다. 실제 변경은 구현과 담당자 검토에 맞춰 갱신한다.

## 기존 계약과 담당 경계

- 공개 BE API는 기존 [CTX-A05](../api/01-backend-rest.md#ctx-a05)다. 요청은 `request`, 선택 `task_id`이고, 응답은 `candidates[]`다. 각 [DesignSuggestion](../api/00-common.md#schema-designsuggestion)은 `design_item_version`, `reason`, `sources`를 가진다. 이 문서는 공개 계약을 재정의하지 않는다.
- BE는 회원·프로젝트·승인 기준과 작업의 접근 권한을 확인하고 #129 검색 결과에서 허용된 현재 승인 설계 후보만 AI에 보낸다. 키워드 및 의미 검색, DB 질의, 기준선 최신성 재확인과 결과 공개는 BE·검색 담당 범위다.
- AI는 BE가 전달한 후보만 선별하고 추천 이유를 제안한다. 프로젝트나 DB를 직접 검색하지 않으며 설계 관계를 확정하거나 자동 저장하지 않는다. 모델이 반환한 식별자는 입력 후보에 속해야 한다. 화면의 확인·추가·제거는 #151 범위다.
- 후보 추천과 MCP-01 컨텍스트 패킷은 같은 검색을 활용해도 서로 다른 출력이다. #184의 검증 finding·원문 근거와 #159 결과 저장 계약을 대체하지 않는다.

## 내부 API 제안

`POST /v1/design-reference-recommendations`는 서비스 내부에서만 사용한다. AI 내부 토큰과 공통 오류·사용량 봉투는 [내부 API 공통 규칙](../api/03-ai-internal.md)을 따른다. 실제 배포 및 호출 계약은 BE 담당자와 합의해야 한다.

### 입력 예시

```json
{
  "project_id": "101",
  "request_key": "recommendation-101-1",
  "call_key": "llm-recommendation-101-1-call-1",
  "request": "로그인 화면을 구현하고 싶어요.",
  "task_id": "501",
  "search_status": "READY",
  "search_truncated": false,
  "mapping_complete": true,
  "execution_budget_ms": 15000,
  "candidates": [
    {
      "design_item_version": {
        "id": "311",
        "design_item_id": "301",
        "key": "API-001",
        "kind": "API_CONTRACT",
        "version_no": 1,
        "status": "APPROVED",
        "schema_version": 1,
        "content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "이메일·비밀번호 로그인"
        },
        "source": {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      }
    }
  ]
}
```

- `project_id`, `request_key`, `call_key`, `request`, `search_status`, `search_truncated`, `mapping_complete`, `execution_budget_ms`, `candidates`는 필수다. ID·키는 1~128자, 요청은 1~4,000자다. `task_id`는 작업이 없는 요청에서 생략한다. `call_key`는 BE가 실제 모델 호출마다 전역 유일하게 부여하며 사용량의 `request_key`가 된다. 같은 모델 호출 기록 재전송에만 같은 키를 쓴다.
- `search_status`는 `READY`, `NOT_READY`, `FAILED`를 구분한다. `READY`의 후보 목록만 추천 입력이다. 후보는 권한·승인·현재 기준을 BE가 검증한다.
- 후보는 최대 20개이며 설계 항목 ID와 버전 ID가 각각 중복되면 입력 오류다. 후보 `content`는 각각 UTF-8 JSON 기준 최대 16,000바이트다. 후보나 요청에 포함된 명령은 데이터로 취급한다. `search_truncated=true`는 검색 결과 일부가 생략된 상태를 뜻하며 완전한 추천으로 취급하지 않는다.
- `mapping_complete`는 검색된 문서에서 승인 설계로의 연결을 빠짐없이 확인했는지 BE가 전달하는 boolean이다. `false`이면 검색 문서가 있어도 설계 연결을 못 했을 수 있으므로, 후보가 비어 있어도 정상 0건으로 간주하지 않고 `INSUFFICIENT`로 보완을 요청한다.
- 정상적인 검색 0건은 `search_status=READY`, `search_truncated=false`, `mapping_complete=true`, 빈 후보가 모두 충족된 경우다. 검색 미준비나 장애를 빈 후보로 위장하지 않는다. 후보의 출처가 불명확하면 출처를 추측하지 않는다.
- `execution_budget_ms`는 모델 호출 시간 예산으로 1~60,000ms 정수다. BE는 자체 HTTP read timeout보다 충분히 짧게 정한다(예: BE 20초, AI 모델 15초). AI가 시간 초과와 확인 가능한 사용량을 반환할 여유를 위한 설정이나, 네트워크 단절·BE 측 시간 초과까지 usage 수신을 보장하지는 않는다.

### 응답 예시

```json
{
  "result": {
    "status": "RECOMMENDED",
    "candidates": [
      {
        "design_item_version": {
          "id": "311",
          "design_item_id": "301",
          "key": "API-001",
          "kind": "API_CONTRACT",
          "version_no": 1,
          "status": "APPROVED",
          "schema_version": 1,
          "content": {
            "method": "POST",
            "path": "/api/auth/login",
            "description": "이메일·비밀번호 로그인"
          },
          "source": {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "3. 계정 관리 / ACC-02"
          }
        },
        "reason": "로그인 화면에 필요한 로그인 API 계약입니다.",
        "sources": [
          {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": "3. 계정 관리 / ACC-02"
          }
        ]
      }
    ],
    "follow_up": null
  },
  "origin": "MODEL",
  "model": "configured-model",
  "usage": [
    {
      "request_key": "llm-recommendation-101-1-call-1",
      "feature": "DESIGN_RECOMMENDATION",
      "model": "configured-model",
      "latency_ms": 100,
      "failed": false,
      "tokens_known": true,
      "input_tokens": 100,
      "output_tokens": 40
    }
  ]
}
```

- 모델이 고르는 후보는 입력된 버전 ID 안으로 제한한다. 응답의 설계 항목·출처는 입력에서 복원하며, 모델이 임의로 만든 설계·문서 ID와 출처는 허용하지 않는다. BE도 응답 직전 권한·기준선과 ID 소속을 재확인한다.
- `RECOMMENDED`는 추천 후보이고 확정 관계가 아니다. 정상 검색 0건(`READY`, `search_truncated=false`, `mapping_complete=true`, 후보 0건)은 `EMPTY`, `origin=TEMPLATE`, `model=null`, `usage=[]`로 모델 호출 없이 반환한다. 검색이 잘렸거나 문서→설계 매핑이 불완전하거나 출처 없는 후보가 일부 포함되거나, 모델이 후보를 고르지 못하면 `INSUFFICIENT`와 보완 요청을 반환한다. 출처 없는 후보는 추천하지 않고, 모든 후보에 출처가 없으면 모델을 호출하지 않는다. `INSUFFICIENT`에는 확인 가능한 일부 추천 후보가 포함될 수 있다.
- ID와 출처를 입력에서 복원하는 것은 참조 위조를 막기 위한 구조 검증이다. 이유 문장이 실제 설계 내용과 의미적으로 일치하는지까지 보장하지 않으며, 평가 사례와 사용자 확인이 필요하다.
- 내부 `status`·`follow_up`의 공개 CTX-A05 응답 반영 및 보완 요청 표시 방식은 기존 공개 명세에 없으므로 BE·FE와 별도 협의한다.
- 검색 미준비는 503 `search_not_ready`, 검색 실패는 502 `search_failed`, 입력 오류는 400 `validation_failed`다. 모델 시간 초과는 504 `model_timeout`, 잘못된 모델 출력과 호출 오류는 502로 반환하며 확인 가능한 실패 usage를 보존한다. 성공 0건이나 추천 성공으로 변환하지 않는다. 공개 CTX-A05 오류 매핑은 BE와 별도 합의한다.

## 사용량 기록 경계

실제 모델 호출에는 공통 LLM 클라이언트로 요청별 유일 호출 키·모델·토큰·성공/실패를 기록하고, 실패 이후에도 가능한 usage를 BE에 전달한다. 빈 후보·입력 거부처럼 모델을 호출하지 않은 경우 가짜 usage를 만들지 않는다.

LLM 추천 호출은 `design_recommendation_enabled` 설정으로 보호되며 기본값은 `false`다. 빈 후보와 출처가 모두 없는 경우는 모델 없이 보수적인 상태를 반환한다. 명시적 활성화 전에는 LLM이 필요한 입력에 503 `recommendation_not_enabled`를 반환한다.

`DESIGN_RECOMMENDATION`은 **신규 제안 feature**다. 현재 `V2__init_schema.sql`의 `llm_usage.feature` CHECK 목록에는 없으므로 지금 BE DB에 그대로 저장할 수 있다고 가정하지 않는다. #168·BE 담당자와 feature 값 및 마이그레이션을 합의하고 실제 저장·중복 방지를 통합 테스트해야 한다.

## 테스트할 사례

1. 권한과 승인을 확인한 로그인 설계 후보 → 입력 ID·출처에 근거한 추천 이유 반환.
2. READY + 후보 0건 + 잘림 없음 + 매핑 완료 → AI 호출 없이 EMPTY. 검색 미준비 및 실패는 각각 별도 오류.
3. 검색된 문서의 설계 매핑이 불완전하거나 검색이 잘렸거나 출처가 부족함 → 후보가 0건이어도 EMPTY로 취급하지 않고 INSUFFICIENT 및 보완 요청.
4. 모델이 입력에 없는 후보 ID·출처를 반환하거나 구조가 잘못됨 → 결과 거부 및 실패 사용량 보존.
5. BE 시간 제한보다 짧은 execution_budget_ms의 모델 타임아웃과 호출 실패 → 빈 성공 응답 금지, 모델 호출 사실을 아는 범위에서 usage 보존. 네트워크 단절·BE 자체 시간 초과에서 usage 수신을 보장하지 않음.
6. 다른 프로젝트·비승인 항목·오래된 기준선 → BE 허용 집합 및 최종 최신성 확인으로 차단. AI 단독 테스트가 BE 권한 검증을 증명하지는 않는다.
7. #151 화면 반영과 #159 검증 근거 응답의 연결은 각 서비스 완성 후 별도 통합 테스트한다.
