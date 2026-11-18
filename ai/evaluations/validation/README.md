# 184번 실제 모델 평가

합성 입력 4건을 FastAPI 내부 `POST /v1/validate`에 보내고, 응답 계약과 기대하는 의미 판단을 함께 확인한다.

| 사례 | 기대 |
| --- | --- |
| 정상 | COMPLETE이며 차단 문제 없음 |
| `userId` / `memberId` 불일치 | 승인 설계 버전과 두 필드 이름을 명시한 BLOCKING MISMATCH |
| 권한 정책 미확정 | PARTIAL과 EVIDENCE_INSUFFICIENT. 정책이 없다는 확정이나 PASS가 아님 |
| 승인 내용과 원문 충돌 | 승인 설계 버전 및 원문 evidence를 참조하는 MISMATCH 또는 CONFLICT |

각 입력은 회원 조회를 표현하는 가상의 데이터이며, MD 발췌 범위와 정규화 원문 SHA-256 해시를 포함한다. `fixtures.py`에서 각 요청과 기대 조건을 확인할 수 있다. 이 평가는 모델을 한 번씩 호출하는 샘플로, 실제 정확도나 재현율의 통계적 보증이 아니다.

## 준비 및 실행

`ai/.env`의 기존 OpenAI API 키 또는 환경변수를 사용한다. 키를 명령어나 채팅에 입력하지 않는다. 키는 결과·콘솔에 저장하거나 출력하지 않는다. 아래 모델명은 예시이며 실제 계정에서 사용할 수 있는 모델로 지정한다. `--run-live`는 **실제 유료 호출 4회**를 수행한다.

```powershell
cd C:\Users\SSAFY\Desktop\jihun\BEEZ\ai
$env:VALIDATION_EVAL_ENDPOINT = 'https://api.openai.com/v1/chat/completions'
$env:VALIDATION_EVAL_MODEL = 'gpt-4o-mini-2024-07-18'
$env:VALIDATION_EVAL_KEY_NAME = 'OPENAI_API_KEY'
.\.venv\Scripts\python.exe -m evaluations.validation.run --run-live --output evaluations/validation/results-live.json
```

GMS는 `VALIDATION_EVAL_KEY_NAME=GMS_KEY`와 해당 호스트의 Chat Completions 주소를 사용한다. 잘못된 호스트로 비밀키를 전송하지 않도록 OpenAI와 GMS 공식 호스트만 허용한다. `--run-live`를 빼면 모델 호출 없이 네 요청의 유효성만 검사한다. 결과 파일에는 HTTP 상태, 모델 응답 원문, 사용량, 기대 조건별 PASS/FAIL이 기록되며 입력은 모두 합성이다. 요금 단가가 설정되지 않으면 비용은 알 수 없음으로 남는다.

평가기에서 FastAPI 라우터를 실제로 실행하되 합성 인증과 선택한 테스트 공급자를 일시 주입한다. 따라서 **BE, 실제 PostgreSQL, 검색, 사용량 DB 저장, 운영 GMS 라우터 설정은 검증하지 않는다.** 모델 출력이 형식 검증에 실패하면 오류와 확보한 호출별 사용량을 결과에 기록하고 정상이라고 표시하지 않는다. 테스트 전용 오프라인 검증은 `python -m pytest -q evaluations/validation/test_evaluation.py`로 실행한다.

## 저장된 결과 오프라인 집계 (#84)

추가 모델 호출 없이 기존 JSON을 읽어 요약한다. 원본과 다른 경로를 지정해야 하며 출력 경로를 생략하면 표준 출력으로 표시한다.

```powershell
python -m evaluations.validation.summary evaluations/validation/results-live-gpt41-final.json
python -m evaluations.validation.summary evaluations/validation/results-live-gpt41-final.json --output artifacts/summary.json
python -m pytest -q evaluations/validation/test_summary.py
```

- 각 응답의 사례 기준은 저장된 `passed` 값을 그대로 세지 않고, 위 표와 기존 평가기의 기준으로 다시 확인한다. 200 응답은 `origin=MODEL`, 오류 없는 봉투, 현재 `ValidateResult` 스키마 및 검사 키 분배·상태 일치를 확인한다. 실패하거나 모델·서버 오류, 전송 실패가 있으면 **관측 불가**다. 성공적인 200 응답의 사용량이 없어도 의미 판단은 관측하고 사용량만 미확인으로 남긴다.
- 저장된 결과 파일에는 당시의 전체 요청 본문이 없다. 집계는 현재 네 고정 fixture의 검사 키 `plan-completeness`와 `referenced-design-311`을 당시에도 사용했다고 가정하고, 각 키가 정확히 한 번 완료 또는 미평가됐는지 검사한다. 출처·원문·입력 스냅샷과의 실제 일치 여부를 이 집계만으로 재검증할 수는 없다.
- 정상 사례의 오탐은 **BLOCKING finding이 하나 이상인 경우**로만 센다. 비차단 finding이 모두 정확하다는 뜻은 아니다. 불일치·원문 충돌은 각각 위 표의 종류·버전·근거 요건이 충족되지 않은 건수이며, 정책 미정은 PARTIAL과 EVIDENCE_INSUFFICIENT 요건이 충족되지 않은 건수다. 이 값은 사례 기준의 미충족이지 일반화할 수 있는 오탐률·재현율이 아니다.
- 비200 `model_unavailable` 응답 이전에 모델 원문이 캡처된 경우는 `model_output_then_rejected`로 분리한다. 공개 오류 코드만으로 JSON 파싱 실패인지 다른 결과 검증 실패인지 확정하지 않는다. 원문 캡처가 없는 모델·서버 오류는 별도다.
- 토큰은 `tokens_known=true`이며 해당 필드가 유효한 비음수 정수인 **보고된 usage 기록**만 합한다. 지연은 보고된 비음수 `latency_ms`만 합하며 총합과 건수를 함께 표시한다. 사용량 기록이 없거나 값을 모르면 0으로 채우지 않고 별도 미확인 건수에 남긴다. 비용은 유효한 비음수 값과 영문 대문자 세 자리 통화가 함께 보고된 경우에만 통화별 Decimal 합계로 표시하며 누락·잘못된 통화는 미확인이다. 추정 단가나 청구액을 임의로 계산하지 않는다.
- 네 사례는 프롬프트를 조정하는 데 사용된 **비독립 합성 사례**다. 결과는 해당 실행의 관측치이며 운영 성능이나 통계적 품질을 입증하지 않는다. 실제 비용·지연과 BE·DB·검색 연동 여부도 별도 확인이 필요하다.
