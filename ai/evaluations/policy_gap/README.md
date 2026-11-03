# 정책 분석 합성 평가 (S15P31A108-186)

2026-11-03에 GMS `gpt-6-luna`, `json_object`로 실행했다. 입력은 이 디렉터리에서 만든 합성 계획과 설계다. 실제 프로젝트 문서나 인증 값은 결과에 포함하지 않았다. 각 결과 JSON에는 합성 입력에 대한 모델 응답, 호출 실패와 사용량이 남아 있다.

| 실행 | 결과 | 범위와 확인한 실패 |
| --- | --- | --- |
| `results-gms-initial.json` | 11/12 | 최초 프롬프트. 계획 불일치 사례에서 승인 설계 문구를 그대로 인용하지 않아 출력 검증이 거부했다. |
| `results-gms-second.json` | 10/12 | 출력 한도와 중복 finding 지시를 변경한 뒤 전체 재실행. 정책 간 충돌에서 보조 근거를 finding에 함께 인용하지 않아 거부했고, 문서 수정만 하는 작업을 근거 부족으로 분류했다. |
| `results-gms-refined.json` | 8/8 | 위 두 사례와 **수정 후 새로 만든 별도 사례 6개**. 여섯 개에는 정상·불일치·명시적 미정·정책 간 모순·검색 근거 부족·주석 수정만 하는 작업이 포함된다. |
| `results-gms-repeated.json` | 12/12 | 같은 수정 후 프롬프트에서 정상·명시적 미정·두 정책의 모순·검색 근거 부족을 각각 3회 실행했다. |

최초 12개 중 원래 holdout 6개는 두 번째 실행 결과를 검토하면서 프롬프트 보완에 사용했으므로 최종 미노출 평가군으로 취급하지 않는다. 최종 미노출 평가는 새로 추가한 6건을 각각 한 번만 호출한 결과다. `refined` 호출 지연은 4.7~14.6초, 중앙값 9.6초였고 `repeated`는 5.5~15.6초, 중앙값 9.7초였다. 최초 실행 중에는 24.8초가 걸린 호출도 있었다. 이 값은 검색 시간을 제외한 모델 호출 지연이며, 작은 합성 표본으로 실제 프로젝트의 재현율·오탐률이나 지연을 추정할 수 없다.

평가 스크립트는 FastAPI ASGI 경로와 실제 GMS 모델을 연결하지만 BE·DB·검색을 사용하지 않는다. 별도로 `PolicyGapLivePipelineIntegrationTest`에서 격리 PostgreSQL, 실제 키워드/벡터 검색, 실제 GMS 문서·검색어 임베딩, FastAPI HTTP와 실제 GMS 모델을 연결했다. 합성 로그인 정책 1건이 검색되어 `SUPPORTED`로 저장·조회되고 검색 후보가 계획의 확정 참조로 바뀌지 않는 것을 확인했다. 이 한 건은 검색 품질 전반을 입증하지 않는다. BE의 오류·부분 결과·양쪽 모순 근거·저장 검사는 별도 격리 DB 테스트에서 검색과 모델을 제어한 대역으로 확인했다.

## 재현

`ai/`에서 다음 명령으로 합성 입력의 형식만 확인할 수 있다. 라이브 실행은 `GMS_KEY`를 안전하게 환경에 주입한 뒤 `--run-live`를 명시한다. `--case`로 사례를 고르고 `--repeat`로 반복 횟수를 1~5회로 제한할 수 있다. 라이브 실행은 선택한 사례와 반복 횟수만큼 과금 가능한 모델 호출을 수행한다.

```shell
python -m evaluations.policy_gap.run --output evaluations/policy_gap/check.json
python -m evaluations.policy_gap.run --run-live --repeat 3 --case login_supported --case missing_search --output evaluations/policy_gap/local-results.json
```

종단 간 테스트는 사용자 데이터를 쓰지 않는 격리 DB와 로컬 FastAPI가 필요하다. 로컬 서버에는 별도 내부 토큰과 `GMS_KEY`를 주입하고, 테스트 프로세스에는 `BEEZ_POLICY_LIVE_AI_URL=http://127.0.0.1:<포트>`와 `BEEZ_POLICY_LIVE_AI_TOKEN`을 지정해 `integrationTest --tests com.beez.backend.validation.PolicyGapLivePipelineIntegrationTest`를 실행한다. 문서와 쿼리 임베딩은 실제 GMS 모델을 사용한다.

## 배포 시 설정

`VALIDATION_POLICY_ANALYSIS_ENABLED`는 기본적으로 `false`다. AI 서버와 마이그레이션·BE·FE의 새 계약이 배포되고 운영 합의가 끝난 뒤 신규 검증에만 켠다. 정책 분석을 켜면 READS만 있는 계획도 비동기 분석 대상이 된다. 이전 큐와 PASS는 기존 규칙 버전을 유지한다.

기본 `VALIDATION_BUDGET_MS=15000`, `AI_READ_TIMEOUT=20s`는 관찰된 일부 모델 호출보다 짧다. 활성화 전에 호출 지연과 검색 시간을 고려해 예산·읽기 제한·리스가 순서대로 충분한지 조정해야 한다. 이번 종단 간 테스트는 각각 60000ms, 75s, 120s를 사용했다. 120초 리스에는 검색 시간도 포함되므로 느린 검색 환경에서 충분하다는 뜻은 아니다.

검색은 계획에 대한 기존 하이브리드 검색을 한 번 사용하며 추출한 정책별 추가 검색을 하지 않는다. 검색 실패와 검색 0건을 정책 부재로 간주하지 않는다. 완료된 검증이 사용한 **검색 후보만** 나중에 바뀐 경우에는 현행 발행 경로가 그 후보를 확정 참조로 취급하지 않아 자동 STALE하지 않는다. 분석 도중 baseline이 바뀌면 무관한 변경이라도 결과를 폐기한다. 이 두 최신성 제한과 후보 의존성 추적의 필요성은 별도 결정이 필요하다.
