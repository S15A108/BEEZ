# 229 실제 화면 연동 확인

`recovery-229`의 격리 Spring 시험이 만든 실제 발행 결과를 Chromium으로 확인한다.
별도 mock 서버나 Playwright 응답 가로채기는 사용하지 않는다. Vite는 `/api`를
시험 중 실행 중인 localhost backend에 전달한다.

저장소의 `frontend`에서 `npm ci`와 `npx playwright install chromium`을 먼저 실행한 뒤,
저장소 루트에서 다음과 같이 실행한다.

```powershell
$env:BEEZ_229_UI = 'true'
python infra/cdc-poc/recovery-229/run.py
```

Java 시험이 격리 데이터의 계정·프로젝트·제안·기대 상태를 임시 JSON으로 전달하고,
종료 시 해당 입력 파일을 지운다. `run.mjs`는 localhost 주소만 허용한다.
계정·비밀번호는 실제 사용자 정보가 아닌 합성 fixture다.

검사 항목:

- 실제 로그인과 세션 쿠키로 API·화면 접근
- 비로그인 401, 다른 프로젝트 계정 404, 허가된 계정 200
- 실제 API의 `published_analysis.status`와 화면 상태 문구
- 첫 로그인 안내를 닫고 변경 제안 상세 화면 진입
- 새로고침 클릭 이후 해당 API가 반환한 GET 200과 상태 본문
- 발행 후 처리 상태와 발행 전 영향 분석의 구분
- 브라우저의 예외 없음

성공하면 `recovery-229/evidence/ui/ui-evidence.json`과
`published-analysis-live.png`를 남긴다. 실패 화면은 진단용이며 성공 증거로 취급하지 않는다.
실제 모델 호출·결과 정확도는 별도 FastAPI/GMS 시험의 범위다.
