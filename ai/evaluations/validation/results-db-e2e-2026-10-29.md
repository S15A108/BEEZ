# PostgreSQL·BE·OpenAI 전체 연결 평가 (2026-10-29)

## 범위

첫 번째 성공 실행: 격리 Testcontainers PostgreSQL(pgvector), 실제 BE 검증 접수·규칙 검사·검색·worker·저장·조회, 로컬 전용 HTTP AI 테스트 서버, OpenAI `gpt-4.1-2025-04-14` strict JSON Schema. 모든 입력은 합성 데이터다. 검색 임베딩은 고정된 합성 벡터이므로 실제 임베딩 모델의 품질·검색 재현율을 평가하지 않았다. 실제 운영 GMS 연결 및 실제 서비스 DB는 사용하지 않았다.

| 사례 | DB에 저장된 검증 상태 | 관측 |
| --- | --- | --- |
| 정상 | PASS | finding 없음. 해당 시점 지정 범위에 대한 PASS |
| userId/memberId 불일치 | NEEDS_REVIEW | MODEL MISMATCH/BLOCKING 및 승인 설계 출처 |
| 정책 미확정 | ERROR | evidence_insufficient / HUMAN_REVIEW. 자동 PASS하지 않음 |
| 승인 내용과 원문 충돌 | NEEDS_REVIEW | MODEL MISMATCH/BLOCKING 및 원문 출처 |

각 사례에서 해당 validation_run에 TASK_VALIDATION usage 1건이 저장됐고 provider `openai`를 확인했다. 첫 실행에서는 사용량 건수만 기록했다. 다음은 동일한 합성 사례를 한 번 더 실행했을 때 **격리 DB에 저장된** 개별 토큰 수다. 두 번째 실행도 4/4 통과했다.

| 사례 | 입력 토큰 | 출력 토큰 | failed |
| --- | ---: | ---: | --- |
| 정상 | 1,729 | 31 | false |
| 불일치 | 1,727 | 214 | false |
| 정책 미확정 | 1,740 | 82 | false |
| 문서 충돌 | 1,729 | 323 | false |
| 합계 | 6,925 | 650 | — |

정책 미확정은 모델 호출 자체가 성공했으므로 usage의 `failed=false`이며, 분석 범위 부족 때문에 BE 판정은 `ERROR/HUMAN_REVIEW`다. 비용은 별도 제공되지 않아 확정하지 않는다. 테스트 컨테이너는 종료 후 제거되므로 이 문서에 사용량을 보존한다.

첫 실행은 4건 모두 BE→AI HTTP 요청 단계에서 ERROR였다. JDK의 HTTP/2 cleartext 업그레이드 시도와 Uvicorn 호환 문제를 확인해 내부 호출을 HTTP/1.1로 고정했다. 또 원문은 승인된 문서·버전과 명시 행 범위가 일치하고 정규화 텍스트 SHA-256이 검증될 때만 evidence로 전달한다. 해당 수정 후 위 실행에서 4/4 테스트가 통과했다.

두 차례 합성 사례 통과는 모델 정확도와 운영 준비를 보장하지 않는다. 모델은 사전에 4건 합성 입력으로 프롬프트를 조정해 독립 평가가 아니다. 긴 문서·추가 검색 후보·원문 locator 없음·미검증 context packet은 보수적으로 통과가 보류될 수 있다. DB concurrency의 실제 경합, 운영 GMS 및 실제 임베딩 서비스는 별도 검증이 필요하다.
