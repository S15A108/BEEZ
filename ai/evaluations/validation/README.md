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
