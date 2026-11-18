# PR AI 리뷰

`dev` 대상 PR을 Claude가 리뷰해 PR 댓글로 남깁니다. 댓글만 달며 코드 수정·승인·머지는 하지 않습니다.

## 동작

| 언제 | 동작 |
|---|---|
| `dev` 대상 PR이 열릴 때, Draft → Ready for review로 바뀔 때 | 자동 리뷰 1회 |
| PR에 `/ai-review`로 **시작하는** 댓글을 달 때 (소유자·멤버·협업자만) | 그 시점 내용으로 다시 리뷰. 댓글 내용은 요청 메모로 함께 전달 (예: `/ai-review 인증 부분 위주로`) |
| 새 커밋을 push할 때 | 아무 일도 안 함 (원하면 `/ai-review` 댓글) |

다시 리뷰할 때마다 새 댓글이 달립니다(커밋별 리뷰 기록이 남도록 이전 댓글은 고치지 않습니다).
봇 댓글에 답글을 달 때는 `/ai-review`로 시작하지 마세요. 시작하면 다시 리뷰가 돕니다.

리뷰 내용
- 기계 검사(LLM 없음): 코드 속 API ID가 `docs/api/*.md`에 있는지, 바뀐 문서의 링크 대상이 있는지, Jira 키 유무, 컨트롤러만 바뀌고 API 명세는 안 바뀐 경우
- Claude 리뷰: 계약·문서 정합성(claim, comment-code, contract, docs-update) + 코드 결함(bug, security, rule)
- Claude 지적은 diff에 실제 있는 파일·줄·인용인지 스크립트가 다시 확인하고, 근거가 없으면 버립니다. 삭제된 줄(예: 권한 검사 삭제)을 근거로 한 지적도 인정합니다.
- **핵심 집중**: 서비스에 영향을 줄 수 있는 중요한 문제를 우선하고, 댓글에는 최대 3개(`max_findings`)만 싣습니다. 순서는 기계 오류 → 확실한 지적 → 확인 필요 순이고, 남는 건 "생략 N건"으로만 표시합니다.
- **사실 기반**: 입력(PR 본문·diff·준 문서)에서 확인되는 것만 사실로 쓰게 하고, 확인할 수 없는 부분에 기대는 지적은 "확인 필요"로 내며 무엇을 확인해야 하는지 적게 합니다.
- **일관성**: Claude CLI는 temperature를 지정할 수 없어서, 근거 검증과 낮은 effort, JSON 고정 출력으로 흔들림을 줄입니다.
- Claude 호출이 실패(시간 초과, 사용량 한도 등)해도 기계 검사 결과는 댓글로 남기고, 실패했다는 한 줄을 덧붙입니다.

계약 문서 선택 방식: 긴 문서(`01-backend-rest.md` 약 41만 자)는 한 번에 12,000자만 보냅니다. diff에 나온 API ID(예: `MEM-A05`)가 제목에 든 `## MEM-A05. ...` 절을 먼저 고르고, 남는 자리에 diff 낱말이 많이 든 절을 점수 순으로 채웁니다.

## 켜는 법 (한 번만)

1. 로컬 터미널에서 `claude setup-token` 실행 → 브라우저 로그인 → 출력된 `sk-ant-oat...` 값 복사
2. GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret
   - Name: `CLAUDE_CODE_OAUTH_TOKEN`, Secret: 위 값
   - (API 키를 쓰려면 이름을 `ANTHROPIC_API_KEY`로)
3. 이 파일들을 `dev`에 머지한다. 워크플로가 `dev`에 있어야 동작하므로(`pull_request_target`, `issue_comment`) 머지 전에는 이 PR에서 리뷰가 돌지 않습니다. 머지 전 시험은 로컬에서 합니다(아래).

토큰은 구독 사용량을 씁니다. 토큰을 낸 사람의 계정으로 사용량이 잡힙니다.

## 조정

`config.json`
- `model` / `effort`: 기본 `opus` / `medium`. 구독 사용량이 부담되면 `sonnet`으로, 더 깊게 보려면 `high`로 (비용·시간 증가)
- `routes`: 바뀐 경로 → 함께 읽힐 문서. 리뷰 품질에 크게 영향을 줍니다. 새 영역이 생기면 추가
- `skip_diff`: diff에서 뺄 경로(정규식)
- `max_diff_chars`: 한 번에 보낼 diff 크기. 넘는 파일은 뒤쪽(문서·테스트)부터 빠지고 댓글에 표시

`extra-checks.md`: 백엔드·프론트 체크리스트. 팀 리뷰에서 되풀이된 실수가 있으면 한 줄씩 추가

## 안전

- 워크플로는 `pull_request_target`이라 **PR이 아니라 `dev`의 워크플로 파일**을 실행합니다. PR이 `ai-review.yml`이나 `review.py`를 고쳐도 그 PR의 리뷰에는 반영되지 않습니다.
- 이 방식은 **PR 코드를 체크아웃·실행하지 않기 때문에** 안전합니다. 워크플로에 PR 브랜치 체크아웃이나 PR 코드 실행(`npm install`, `gradlew` 등)을 넣으면 secret이 노출될 수 있으니 넣지 마세요. PR 내용은 git 객체로만 읽습니다.
- Claude는 도구·MCP·슬래시 명령을 모두 끈 채 글만 읽고 답합니다.
- fork PR은 자동 리뷰하지 않습니다.
- 비밀값: `sk-ant-`, `ghp_`, `glpat-`, `AKIA`, 개인키처럼 **형식이 고정된 토큰**은 Claude에 보내기 전에 가리고, 추가된 줄에서 발견되면 🔴로 알립니다(테스트·픽스처·목업 경로는 경고하지 않습니다). `password = "..."` 같은 일반 대입문과 JWT 샘플은 가리기만 하고 경고는 내지 않습니다.
- **secret 노출 범위**: 등록한 `CLAUDE_CODE_OAUTH_TOKEN`은 개인 구독 토큰입니다. 값은 UI로 볼 수 없지만, 저장소 쓰기 권한이 있는 팀원은 브랜치에 새 워크플로를 올려 secret을 꺼내 쓸 수 있습니다(이 워크플로의 설계와 무관하게 Actions secret의 일반 특성). 팀원을 신뢰하는 전제이고, 의심되면 secret을 지우고 토큰을 재발급하세요.

## 로컬 시험

- PR 없이 현재 브랜치 시험: `python .github/ai-review/review.py --local-base origin/dev` (화면 출력만, `gh` 불필요)
  - Windows에서 `claude.cmd`가 긴 인자를 깨뜨리면 `claude.exe`를 지정: PowerShell `$env:REVIEW_CLAUDE_BIN = "C:\Users\<사용자>\AppData\Roaming\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe"`
- 열린 PR로 시험: `gh` 로그인 후 `python .github/ai-review/review.py --pr <번호> --dry-run` (`--no-llm`이면 기계 검사만)
