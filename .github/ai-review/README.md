# PR AI 리뷰

`dev` 대상 PR을 Claude가 리뷰해 PR 댓글로 남깁니다. 댓글만 달며 코드 수정·승인·머지는 하지 않습니다.

## 동작

| 언제 | 동작 |
|---|---|
| `dev` 대상 PR이 열릴 때, Draft → Ready for review로 바뀔 때 | 자동 리뷰 1회 |
| PR에 `ai-review`가 들어간 댓글을 달 때 (소유자·멤버·협업자만) | 그 시점 내용으로 다시 리뷰. 댓글 내용은 요청 메모로 함께 전달 |
| 새 커밋을 push할 때 | 아무 일도 안 함 (원하면 `ai-review` 댓글) |

리뷰 내용
- 기계 검사(LLM 없음): 코드 속 API ID가 `docs/api/*.md`에 있는지, 바뀐 문서의 링크 대상이 있는지, Jira 키 유무, 컨트롤러만 바뀌고 API 명세는 안 바뀐 경우
- Claude 리뷰: 계약·문서 정합성(claim, comment-code, contract, docs-update) + 코드 결함(bug, security, rule)
- Claude 지적은 diff에 실제 있는 파일·줄·인용인지 스크립트가 다시 확인하고, 근거가 없으면 버립니다.
- **핵심 집중**: 서비스에 영향을 줄 수 있는 중요한 문제를 우선하고, 댓글에는 최대 3개(`max_findings`)만 싣습니다. 순서는 기계 오류 → 확실한 지적 → 확인 필요 순이고, 남는 건 "생략 N건"으로만 표시합니다.
- **사실 기반**: 입력(PR 본문·diff·준 문서)에서 확인되는 것만 사실로 쓰게 하고, 확인할 수 없는 부분에 기대는 지적은 "확인 필요"로 내며 무엇을 확인해야 하는지 적게 합니다.
- **일관성**: Claude CLI는 temperature를 지정할 수 없어서, 근거 검증과 낮은 effort, JSON 고정 출력으로 흔들림을 줄입니다.

## 켜는 법 (한 번만)

1. 로컬 터미널에서 `claude setup-token` 실행 → 브라우저 로그인 → 출력된 `sk-ant-oat...` 값 복사
2. GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret
   - Name: `CLAUDE_CODE_OAUTH_TOKEN`, Secret: 위 값
   - (API 키를 쓰려면 이름을 `ANTHROPIC_API_KEY`로)
3. 이 파일들을 `dev`에 머지한다. (`issue_comment` 트리거는 기본 브랜치의 워크플로만 동작하므로 머지 후부터 댓글 요청이 됩니다.)

토큰은 구독 사용량을 씁니다. 토큰을 낸 사람의 계정으로 사용량이 잡힙니다.

## 조정

`config.json`
- `model` / `effort`: 기본 `opus` / `medium`. 구독 사용량이 부담되면 `sonnet`으로, 더 깊게 보려면 `high`로 (비용·시간 증가)
- `routes`: 바뀐 경로 → 함께 읽힐 문서. 리뷰 품질에 크게 영향을 줍니다. 새 영역이 생기면 추가
- `skip_diff`: diff에서 뺄 경로(정규식)
- `max_diff_chars`: 한 번에 보낼 diff 크기. 넘는 파일은 뒤쪽(문서·테스트)부터 빠지고 댓글에 표시

`extra-checks.md`: 백엔드·프론트 체크리스트. 팀 리뷰에서 되풀이된 실수가 있으면 한 줄씩 추가

## 안전

- PR 코드를 체크아웃·실행하지 않습니다. 스크립트·설정은 항상 `dev` 것을 씁니다.
- Claude는 도구·MCP·슬래시 명령을 모두 끈 채 글만 읽고 답합니다.
- 토큰처럼 보이는 값은 Claude에 보내기 전에 가립니다. 추가된 줄에서 발견되면 🔴로 알립니다.
- fork PR은 secret이 전달되지 않아 자동 리뷰하지 않습니다.

## 로컬 시험

`gh` 로그인이 필요합니다. `python .github/ai-review/review.py --pr <번호> --dry-run` (`--no-llm`이면 기계 검사만)
