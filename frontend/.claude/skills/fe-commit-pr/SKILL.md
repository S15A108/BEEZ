---
name: fe-commit-pr
description: frontend 변경을 팀 컨벤션(docs/collaboration-convention.md)대로 커밋하거나 push, PR을 만들 때 쓰는 절차. 브랜치의 Jira 키로 커밋 메시지와 PR 제목을 만들고, PR 템플릿을 채워 dev 대상 Draft PR을 만든다. 커밋, push, PR은 사용자가 요청했을 때만 한다.
---

# fe-commit-pr

팀 규칙 원문은 레포 루트의 `docs/collaboration-convention.md`다. 이 스킬과 원문이 다르면 원문을 따르고 사용자에게 알린다.

## 커밋

1. 사용자가 커밋을 요청했는지 확인한다. 요청이 없으면 하지 않는다.
2. `fe-verify`가 통과한 상태인지 확인한다. 실패한 채로는 커밋하지 않고 보고한다.
3. 브랜치 이름에서 Jira 키(`S15P31A108-숫자`)를 꺼낸다. 키가 없으면 사용자에게 묻는다.

   ```bash
   git branch --show-current
   ```

4. 관련 파일만 경로를 지정해 스테이징한다. `git add -A`나 `git add .`은 쓰지 않는다. 스테이징 목록에 `node_modules`, `dist`, `test-results`, `.env`가 섞이지 않았는지 확인한다.
5. 메시지 형식은 `타입: 작업 내용 (S15P31A108-N)`이다.
   - 타입: `feat` 기능, `fix` 버그, `design` UI/CSS만 변경, `text` 문구/오타, `refactor`, `comment` 주석, `docs`, `test`, `build` 빌드/의존성, `rename`, `delete`, `chore` 설정/기타
   - 작업 내용은 한글로 쓰고 끝에 마침표를 붙이지 않는다.
   - 하나의 논리적 변경을 커밋 하나로 만든다. 성격이 다른 변경이 섞였으면 나눠서 커밋할지 묻는다.
   - 이유를 남길 필요가 있으면 빈 줄 뒤 본문에 쓴다.
6. `git log --oneline -3`으로 확인하고 보고한다.

## push와 PR

1. 사용자가 명시적으로 요청했을 때만 한다. 실행 전에 브랜치, 대상 브랜치(`dev`), PR 제목을 보여주고 확인받는다.
2. `git fetch origin`을 실행한다. 브랜치가 `origin/dev`보다 뒤처졌으면 알리고, 합칠지는 사용자 지시를 따른다.
3. `git push -u origin <브랜치>`로 push한다.
4. PR 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 양식(개요, 작업 내용, 변경 사항, 스크린샷, 관련 이슈, 체크리스트)을 채워 임시 파일로 만든다.
   - 관련 이슈: `S15P31A108-N`과 `https://ssafy.atlassian.net/browse/S15P31A108-N`
   - 체크리스트: 실제로 확인한 항목만 체크한다.
5. Draft PR을 만든다. 제목은 커밋 메시지 첫 줄과 같은 형식이다.

   ```bash
   gh pr create --draft --base dev --title "<타입: 작업 내용 (키)>" --body-file <본문 파일>
   ```

6. PR 링크를 보고한다. Jira 상태는 바꾸지 않는다(팀 자동화 규칙이 처리한다).
