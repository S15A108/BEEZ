# 변경 영향 분석 기본 모듈 (#188)

이 모듈은 변경 제안과 승인 설계, 관련 작업을 공통 LLM 클라이언트로 분석한다. 내부 API `POST /v1/impact`는 AI-A03 초안을 바탕으로 하며 DB 조회·저장, 규칙 관계 조회, CDC 연결은 구현하지 않았다. 입력은 BE가 프로젝트 권한과 승인 상태를 확인한 뒤 전달한다.

## 흐름

1. BE가 권한을 확인한 프로젝트의 변경 제안, 승인 설계, 명시 의존 관계와 열린 작업을 전달한다.
2. `ImpactInput`으로 입력을 구성하고 `ImpactAnalysisService.analyze()`에 공통 `LLMClient`와 모델 설정을 주입한다.
3. 모델의 후보에서 입력에 없는 작업·담당자·설계 ID와 문서 출처를 거부한다. 작업과 담당자가 함께 있으면 둘의 관계도 확인한다.
4. 결과 또는 공통 오류에 호출별 사용량이 보존된다. 결과는 검토 후보이며 자동 승인이나 작업 상태 변경을 뜻하지 않는다.

문서 출처가 없는 후보는 빈 `sources`를 허용한다. 빈 후보 목록은 제공된 입력에서 모델이 후보를 내지 않았다는 뜻이며, 전체 프로젝트에 영향이 없다는 보장은 아니다. 이 확인은 ID·출처가 입력에 존재하는지만 검사하며 AI 설명이 사실임을 보장하지 않는다.

### 합의 전 확장 필드

현재 `result`에 추가한 `evidence_status` (`SUFFICIENT` 또는 `INSUFFICIENT`)와 후보의 선택적 `validation_id`는 기존 AI-A03 계약에 없는 **확장 제안**이다. BE 계약과 저장 형식에 반영하기 전까지 이 API 응답 형식은 확정이 아니다. 입력 작업에 포함된 `validation.id`만 반환할 수 있다. 승인된 기준 버전·원문 출처 또는 작업 계획이 누락되거나 전달된 작업 목록이 비어 있으면 모델이 충분하다고 답하더라도 `INSUFFICIENT`로 반환한다. `SUFFICIENT`는 분석된 정보가 완전하다는 보증이나 영향 없음 판정이 아니다.

### 실행 설정

`ai/.env.example`을 참고해 내부 인증 토큰과 `IMPACT_LLM_ENDPOINT`, `IMPACT_LLM_API_KEY`, `IMPACT_LLM_MODEL`을 설정한다. endpoint는 `/chat/completions`로 끝나는 HTTPS URL이어야 한다(로컬 호스트 HTTP는 개발용 허용). 응답 모델명이 요청 모델과 다를 수 있다면 신뢰할 별칭만 `IMPACT_LLM_ALLOWED_RESPONSE_MODELS`에 쉼표로 구분해 명시한다. 설정되지 않은 모델명은 거부한다. GMS의 단일 시험 외 공급자·모델 조합의 호환성은 배포 전에 확인해야 한다. 설정이 없거나 유효하지 않으면 이 기능은 `model_unavailable`로 실패한다. 서버가 종료될 때 공급자의 HTTP 연결도 닫힌다.

`IMPACT_LLM_RESPONSE_FORMAT`은 기본 `json_object`이며, 공급자와 모델이 엄격한 구조화 출력을 지원할 때만 `json_schema`로 설정한다. 서버가 스키마를 받아도 후보 ID와 출처 검증을 별도로 수행한다. 미지원 또는 잘못된 형식 설정은 명시적으로 실패하고 더 느슨한 형식으로 자동 재시도하지 않는다.

요청 제한 기본값은 전체 JSON 262,144바이트와 입력 목록당 100개다. `IMPACT_MAX_BODY_BYTES`와 `IMPACT_MAX_ITEMS_PER_LIST`로 조정할 수 있으며 각각 1,024~1,048,576바이트, 1~1,000개만 허용한다. 스트리밍 본문의 실제 크기를 확인하고 크기 초과는 413, 목록 초과는 422로 모델 호출 전에 실패한다. 이 413 동작은 AI-A03 기존 초안에 없는 확장이다.

호출마다 출력 토큰 상한을 전송한다. 기본 `IMPACT_LLM_OUTPUT_TOKEN_LIMIT`는 1,024이며 허용 범위는 1~4,096이다. `IMPACT_LLM_OUTPUT_TOKEN_PARAMETER`의 기본값은 `max_completion_tokens`이고, 공급자가 이전 옵션을 요구하면 `max_tokens`로 바꿀 수 있다. 공급자·모델이 선택한 옵션을 지원하는지 배포 전에 확인해야 하며, 지원하지 않으면 호출 실패로 처리하고 다른 옵션으로 자동 재시도하지 않는다. 이 상한은 입력 토큰이나 총 비용을 보장하는 한도가 아니다. 출력이 잘리면 부분 결과를 성공으로 반환하지 않고 모델 오류로 처리한다.

모델 호출 시간 제한 `IMPACT_LLM_TIMEOUT_SECONDS`의 기본값은 15초다. BE AI 클라이언트의 기본 읽기 제한은 20초이므로 직렬화·네트워크에 여유를 남긴다. 두 값을 변경할 때는 AI 시간 제한을 BE 읽기 제한보다 충분히 짧게 설정해야 한다. 외부 시간 초과나 네트워크 단절 시 응답의 사용량 전달까지 보장되지는 않는다.

## 검증

`ai/`에서 다음 명령을 실행한다. 가짜 제공자를 사용하므로 API 키나 외부 모델 호출이 필요 없다.

```bash
python -m pytest -q tests/test_change_impact_service.py
```

[평가 사례](../../../evaluations/change_impact/README.md)는 사람이 작성한 가상 시나리오이며, 실제 모델 평가 결과가 아니다.

### BE와 AI의 HTTP 연동 시험

이 시험은 명시적으로 켤 때만 실행된다. Docker와 Java 21, `ai/.venv`의 프로젝트 의존성이 필요하다. `ai/.venv`가 아닌 Python을 사용할 때는 `BEEZ_E2E_PYTHON`에 해당 실행 파일의 절대 경로를 지정한다. 시험마다 격리된 Testcontainers PostgreSQL(pgvector)과 별도 FastAPI 프로세스를 사용하며, 실제 환경 설정이나 운영 DB를 바꾸지 않는다.

PowerShell에서 `backend/`로 이동해 실행한다.

```powershell
$env:BEEZ_RUN_AI_E2E = "true"
Remove-Item Env:BEEZ_IMPACT_E2E_REAL_GMS -ErrorAction SilentlyContinue
.\gradlew.bat integrationTest --no-daemon --rerun-tasks --tests "com.beez.backend.impactanalysis.ImpactBackendFastApiE2ETest"
```

기본 시험은 가상 데이터를 사용하고 영향 분석·임베딩 제공자와 토큰 계산기를 테스트 대역으로 바꾼다. 테스트는 공개 BE API가 없어 Spring의 영향 분석 서비스를 직접 호출한 뒤, BE 공통 클라이언트에서 별도 FastAPI까지 실제 HTTP로 연결한다. 영향 분석 성공과 모델 타임아웃 시 사용량, 문서 청킹·임베딩을 확인한다. 타임아웃은 테스트 제공자가 반환한 오류이며 실제 네트워크 지연 시험은 아니다. BE의 사용자용 HTTP API를 통한 검증은 아니다.

실제 GMS 시험은 별도로 선택한다. `BEEZ_IMPACT_E2E_REAL_GMS=true`와 테스트 전용 `BEEZ_IMPACT_E2E_GMS_KEY`를 프로세스 환경에 안전하게 주입한 다음 아래 단일 테스트만 실행한다. 키는 소스 파일이나 명령행에 적지 않는다. 실제 외부 호출과 사용 비용이 발생한다. 아래 명령은 영향 분석 성공 사례만 실행한다.

```powershell
$env:BEEZ_RUN_AI_E2E = "true"
$env:BEEZ_IMPACT_E2E_REAL_GMS = "true"
.\gradlew.bat integrationTest --no-daemon --rerun-tasks --tests "com.beez.backend.impactanalysis.ImpactBackendFastApiE2ETest.submittedRevisionCrossesRealHttpBoundaryAndKeepsRulePhaseUnevaluated"
```

최종 테스트 설정에서 가상 변경 제안으로 GMS `gpt-6-luna`를 한 번 호출한 결과 AI 후보 1건과 `evidence_status=INSUFFICIENT`, 사용량 입력 878·출력 724 토큰이 보고됐다. 규칙 후보가 아직 연결되지 않아 BE 최종 상태는 `INSUFFICIENT_EVIDENCE`였다. 단일 호출 성공은 분석 품질이나 반복 안정성을 보장하지 않는다. 이전 서버 기동 실패는 원인을 확인하지 못했고 재현되지 않았다.

공통 BE `AiClient`는 Uvicorn의 httptools에서 h2c 업그레이드와 chunked 요청이 겹칠 때 발생한 HTTP 400을 피하도록 HTTP/1.1을 사용한다. #197의 결과·사용량 저장 및 #227 CDC 연동은 이 시험 범위 밖이다.

AI-A03 직접 요청은 `CREATE`에서 `kind`, `schema_version`, `proposed_content`, `UPDATE`에서 항목 ID·기준 버전·`schema_version`·`proposed_content`, `RETIRE`에서 항목 ID·기준 버전을 요구한다. 이 필드가 누락되거나 작업 계획 텍스트 타입이 잘못되면 모델 호출 전에 오류로 처리한다.

머지된 #195는 변경 제안의 불변 제출 리비전에 `schema_version`과 변경 내용을 보존한다. #188 BE 초안은 이 스냅샷의 리비전·해시를 검증하고 값이 있는 `CREATE`·`UPDATE`를 AI에 전달한다. 기존 제안에 필요한 버전이나 내용이 없으면 값을 추측하지 않고 모델 호출 없이 미평가로 남긴다. 문서 전용·설계와 문서가 섞인 제안은 원문 변경 입력 계약이 아직 없어 `document_change_unavailable`로 미평가하며, 영향이 없다는 뜻이 아니다.

규칙 기반 영향 후보는 현재 `NOT_CONNECTED`다. AI 응답의 `evidence_status`는 모델에 전달된 범위의 진단 정보이며, 그 값이나 빈 후보만으로 전체 분석 완료·영향 0건을 판단하면 안 된다. BE 코디네이터는 규칙 후보가 미연결이면 전체 상태를 `INSUFFICIENT_EVIDENCE`로 남긴다. #197 결과·사용량 DB 저장과 저장 시 최신성 검사의 원자성은 아직 연결되지 않았다.

## 후속 합의·구현

- 규칙 후보·경로를 AI-A03에 전달하는 방식과 AI의 추가 후보 탐색 범위.
- 승인 전 영향 분석과 승인 후 CDC 동기화의 시점·입력 버전.
- 원문 검색, 정보 부족 표현과 validation ID의 BE·저장 계약 확정, 실제 모델 평가 결과 기록.

사용 모델과 호출별 고유 키는 호출자가 설정한다. 테스트 코드의 식별자와 모델명은 예시다.
