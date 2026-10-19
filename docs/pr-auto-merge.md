# PR 승인 후 자동 병합

`dev` 또는 `main` 대상 PR을 만들거나 Draft에서 리뷰 가능 상태로 변경하면
`Enable PR auto-merge` Actions가 Merge commit 방식으로 자동 병합을 예약한다.
재개·커밋 추가·PR 제목 및 대상 변경 때도 예약 상태를 다시 확인한다.

- 쓰기 권한이 있는 리뷰어 1명의 승인, 리뷰 대화 해결 및 저장소 보호 조건을 모두 충족해야 병합된다.
- Draft PR, 닫힌 PR, 다른 대상 브랜치는 처리하지 않는다.
- 승인을 자동으로 작성하거나 관리자 권한으로 보호 조건을 우회하지 않는다.
- 이미 병합 가능한 PR에서 실행하면 즉시 병합될 수 있다.
- 새 커밋으로 기존 승인이 취소되면 다시 승인받아야 한다.
- 이미 자동 병합이 예약된 PR은 기존 예약을 유지한다. 수동 예약도 Merge commit 방식을 사용한다.

## 최초 설정

GitHub BEEZ → Settings → Secrets and variables → Actions → Repository secrets에
`PR_AUTOMERGE_TOKEN`을 등록한다. 토큰 값은 저장소·문서·채팅에 기록하지 않는다.

GitHub fine-grained personal access token을 만들 때:

- Resource owner: `S15A108`
- Repository access: `BEEZ`만 선택
- Repository permissions: Contents **Read and write**, Pull requests **Read and write**
- 만료일을 정하고, 조직 승인이 요구되면 승인 후 사용
- 브랜치 보호 우회 권한이 없는 팀 계정의 토큰 사용

기본 `GITHUB_TOKEN` 대신 전용 토큰을 쓰는 이유는 자동 병합이 만든 Push에서도
후속 `Sync GitLab` Actions가 실행되게 하기 위해서다. 토큰이 없으면 자동 병합
예약 작업은 실패하며, 일반 리뷰 및 수동 병합은 계속 사용할 수 있다.

이 워크플로는 PR 코드를 체크아웃하거나 실행하지 않는다. PR 번호·제목 등은
환경 변수 또는 인자로만 사용한다.

## 기존 PR에 적용

워크플로가 기본 브랜치 `dev`에 병합된 후 Actions → Enable PR auto-merge →
Run workflow에서 `pr_number`에 기존 PR 번호를 입력한다.
워크플로 도입 PR 자체는 기존 방식으로 리뷰 후 병합한다.

## 확인과 실패 대응

1. 해당 Actions 실행이 성공했는지 확인한다.
2. PR에 자동 병합 예약이 표시되는지 확인한다.
3. 승인 및 보호 조건 충족 후 Merge commit으로 병합되는지 확인한다.
4. `Sync GitLab` 성공과 GitLab 같은 이름 브랜치의 커밋 반영을 확인한다.
5. Jira는 별도 GitLab Webhook의 조건과 감사 로그를 확인한다.

인증 실패 시 토큰 만료·조직 승인·저장소 권한을 확인한다.
외부 기여자의 Push나 PR 상태 변경으로 예약이 취소된 경우 Actions 재실행 또는
수동 Enable auto-merge로 다시 예약한다. GitHub의 Actions 정책에서
`pull_request_target` 실행이 차단되면 저장소 관리자가 해당 정책을 검토해야 한다.
