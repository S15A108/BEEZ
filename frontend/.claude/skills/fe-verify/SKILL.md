---
name: fe-verify
description: frontend/ 코드를 바꾼 뒤 완료를 보고하거나 PR을 올리기 전에 실행하는 검증 절차. npm run check(ESLint, Prettier 포맷 검사, Vitest, 타입 체크와 빌드)와 Playwright E2E를 돌리고 단계별 결과를 보고한다.
---

# fe-verify

frontend 변경을 완료라고 보고하기 전에 아래를 순서대로 실행한다. 명령은 모두 `frontend/`에서 실행한다.

1. `node_modules`가 없거나 `package-lock.json`이 바뀌었으면 `npm ci`.
2. `npm run check`. lint, 포맷 검사, 테스트, 빌드를 차례로 돌리고 처음 실패한 곳에서 멈춘다.
   - 포맷 검사가 실패하면 `npm run format`으로 고친 뒤 다시 실행한다.
   - 테스트가 실패하면 코드를 고친다. 실패한 테스트를 지우거나 `skip`해서 통과시키지 않는다.
3. `npm run test:e2e`. 개발 서버를 띄우고 Chromium에서 E2E를 돌린다.
   브라우저가 없다는 에러가 나면 `npx playwright install chromium`을 실행한 뒤 다시 돌린다.
4. 단계별 결과를 보고한다. 실패하면 첫 에러 줄을 그대로 인용하고, 원인을 고친 뒤 실패한 단계부터 다시 실행한다.

실패한 단계가 남아 있으면 완료라고 보고하지 않는다. 경고(warning)는 보고하되 실패로 치지 않는다.
