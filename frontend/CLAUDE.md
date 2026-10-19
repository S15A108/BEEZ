# frontend

@README.md

개인 설정이 있으면 아래 파일을 추가로 읽습니다. 파일이 없는 팀원에게는 아무 영향이 없습니다.

@~/.claude/beez-frontend.md

## 작업별 스킬

| 작업                                    | 스킬           |
| --------------------------------------- | -------------- |
| 기능 추가 (화면, API, 폼, 상태, 테스트) | `fe-feature`   |
| 백엔드 API 연동, 스키마, 에러 처리      | `fe-api`       |
| 3D 씬, 3D 모델                          | `three-scene`  |
| 완료 보고 전 검증                       | `fe-verify`    |
| PR 전 셀프 리뷰                         | `fe-review`    |
| 커밋, push, PR                          | `fe-commit-pr` |

## 팀 컨벤션 요약

원문은 [docs/collaboration-convention.md](../docs/collaboration-convention.md)입니다. 요약과 원문이 다르면 원문을 따릅니다.

- **브랜치**: `<타입>/frontend-<기능>-<Jira 키>` (예: `feature/frontend-dashboard-S15P31A108-12`). 최신 `dev`에서 만듭니다.
- **커밋**: `타입: 작업 내용 (Jira 키)`. 타입은 영문 소문자(`feat`, `fix`, `design`, `text`, `refactor`, `comment`, `docs`, `test`, `build`, `rename`, `delete`, `chore`), 내용은 한글로 씁니다.
- **PR**: 제목은 커밋과 같은 형식이고 `dev` 대상입니다. `.github/PULL_REQUEST_TEMPLATE.md`를 채우고, 1명 승인 후 Merge commit으로 합칩니다.

## 백엔드 계약

`dev`의 백엔드 설정으로 확인한 내용입니다. 자세한 절차는 `fe-api` 스킬에 있습니다.

- 모든 API는 `/api/**` 아래에 있습니다. 개발 서버가 `localhost:8080`으로 프록시합니다.
- JSON 키는 snake_case이고 스키마와 타입도 snake_case 그대로 씁니다([D10](docs/decisions.md)).
- 에러 응답은 `{ "error": { "code": "...", "message": "..." } }` 형식입니다.
- 시간은 UTC로 옵니다. 화면에 보여줄 때 `Asia/Seoul`로 바꿉니다.
- 연결 확인은 `GET /api/ping`, API 문서는 백엔드를 로컬에서 실행했을 때 `/v3/api-docs`입니다.
- AI 서버는 직접 호출하지 않고 백엔드를 거칩니다.

## 완료의 기준

- `npm run check`와 `npm run test:e2e`가 통과합니다.
- 새 로직, 폼, 입력 컴포넌트에는 단위 테스트가 있고, 새 페이지나 라우트에는 E2E가 있습니다.
- 스택, 구조, 포트를 바꿨으면 README를, 결정을 바꾸거나 새로 정했으면 `docs/decisions.md`를 고쳤습니다.

## 작업 규칙

- `frontend/` 밖의 파일(레포 루트 설정, 다른 앱 폴더)은 수정하지 않습니다. 팀 공용 설정은 팀장이 관리합니다. 바꿔야 할 것 같으면 먼저 사용자에게 알립니다.
- 명령은 `frontend/`에서 실행합니다. 패키지 매니저는 npm만 씁니다. pnpm이나 yarn을 쓰면 lockfile이 섞입니다.
- 도구나 구조를 바꾸기 전에 `docs/decisions.md`를 확인합니다.
- 의존성을 추가하기 전에 이미 설치된 패키지나 브라우저 기본 기능으로 해결되는지 확인하고, 추가하기 전에 사용자에게 묻습니다.
- 요구사항이 애매하면 추측해서 만들지 않고 먼저 묻습니다. 백엔드 응답 형태를 모르면 지어내지 않습니다.
- 파일을 고친 뒤에는 `npm run format`으로 포맷합니다.
- API 응답은 Zod 스키마로 파싱하고, 폼은 React Hook Form + Zod로 만들고, 스타일은 Tailwind 클래스로 씁니다. README 코드 컨벤션의 예제 패턴을 따릅니다.
- UI 요소(버튼, 입력, 다이얼로그 등)는 직접 만들기 전에 shadcn에 있는지 확인하고 `npx shadcn add <이름>`으로 추가합니다.
- 테스트가 실패하면 코드를 고칩니다. 테스트를 지우거나 `skip`하거나 기대값을 바꿔서 통과시키지 않습니다. 기대값이 틀렸다고 판단되면 바꾸기 전에 사용자에게 알립니다.
- 커밋, push, PR은 요청받았을 때만 합니다. `.env`와 시크릿은 커밋하지 않습니다.
