# 15. 저장소 연결과 머지 게이트

S15P31A108-421 · GIT-01·05 일부(확장에서 앞당김) · TSK-04 · 작성 전진 · 2026-11-13 · **상태: 제안(팀 합의 전)**

## 1. 왜 하나

여럿이 작업을 나눠 할 때, 코드 쪽에 다음 세 가지 빈칸이 있다.

| 빈칸 | 지금 코드 |
| --- | --- |
| 저장소 주소를 BEEZ 밖에서 따로 관리한다 | `project`에 저장소 칸이 없다. 백엔드에 GitHub 연동이 없다 |
| push·PR 없이도 완료할 수 있다 | `report_task_state(COMPLETED)` 입력은 요약·delta·완료 기준·바꾼 설계 항목뿐이다. 지금 완료는 에이전트가 스스로 알리는 보고다 |
| pull 없이 다음 작업을 시작할 수 있다 | `StartGate` G3는 선행 작업이 BEEZ에서 `COMPLETED`인지만 본다. 그 코드가 기준 브랜치에 들어갔는지, 내가 그 코드를 받았는지는 보지 않는다 |

목표는 하나다. **선행 작업의 PR이 기준 브랜치에 머지되고, 다음 작업자가 그 머지 커밋을 받은 상태여야 다음 작업을 시작한다.** PR은 에이전트가 열든 사람이 열든 상관없다.

10.22 명세는 GitHub 연동(GIT-01~06·TRC-08)을 확장으로 두었다([planning-v3](../planning-v3.md) 12장 결정 4). 이 문서는 그중 저장소 연결(GIT-01)과 머지 확인(GIT-05 일부)만 MVP로 당겨 온다. 웹훅·PR 체크·코멘트(G4)는 그대로 확장으로 남긴다. **이 앞당김은 팀 합의가 필요하다.**

## 2. 정한 것

| # | 결정 | 이유 |
| --- | --- | --- |
| D1 | **새 상태를 만들지 않는다.** `COMPLETED`는 지금처럼 "구현을 끝냈다"는 뜻이고, 머지 여부는 작업 상태와 따로 기록한다. 전이는 D8 하나만 더한다 | 머지를 상태로 만들면 [04-task-lifecycle](04-task-lifecycle.md)의 전이 표와 S1~S5를 전부 고쳐야 한다. 다음 작업을 막는 일은 G3만 바꿔도 된다 |
| D2 | **머지 확인은 START·RESUME 때 GitHub에 직접 묻는다.** 웹훅을 쓰지 않는다 | 서버만으로 끝나고, 웹훅 수신·서명 검증·재전송 처리가 필요 없다. 그 대신 "머지됐다" 알림은 못 보낸다(§9) |
| D3 | **머지 게이트는 `BLOCKS` 선행 관계에만 건다.** 선행 관계가 없는 작업은 기준 브랜치보다 뒤처졌다는 **경고만** 준다 | 관계가 없는 작업까지 막으면 서로 상관없는 머지 때문에 매번 pull을 강제하게 된다 |
| D4 | **GitHub 인증은 프로젝트별 읽기 전용 fine-grained PAT**를 쓴다. 서버 키로 암호화해 저장하고, API로 다시 돌려주지 않는다. 공개 저장소는 토큰 없이도 쓸 수 있다 | GitHub App은 설치·권한 관리가 깔끔하지만 등록·설치 흐름이 무겁다. 나중에 App으로 바꿔도 저장 칸 하나만 바뀐다 |
| D5 | **저장소를 연결하지 않은 프로젝트는 지금과 똑같이 동작한다** | 기존 프로젝트·시험·시연 데이터가 그대로 돈다 |
| D6 | **GitHub를 조회할 수 없으면 시작을 막는다**(fail closed). START는 다시 시도할 수 있다 | 게이트를 조용히 통과시키면 이 기능을 넣는 의미가 없다. 한 번 확인한 머지 사실은 바뀌지 않으므로 저장해 두고 다시 묻지 않는다(§6) |
| D7 | **머지된 PR이 나중에 revert된 경우는 잡지 않는다.** 알려진 한계로 둔다 | revert를 판정하려면 기준 브랜치 이력을 해석해야 한다. 비용에 비해 드물다 |
| D8 | **PR이 아직 머지되지 않았으면 `COMPLETED` 작업도 `RESUME`할 수 있다.** 완료한 뒤 코드를 다시 고치는 일(리뷰 반영·머지 충돌 해소)은 모두 RESUME부터 시작한다(§7) | 머지 게이트로 "완료했지만 머지 전" 구간이 생긴다. 이 구간의 코드 변경을 기록 밖에 두면 BEEZ가 아는 것과 실제 머지되는 코드가 갈라진다. RESUME을 거치면 시작 게이트와 DELTA 검증(S3)이 그대로 돈다 |
| D9 | **PR의 머지 가능 여부(`mergeable_state`)를 읽어 작업에 "충돌 있음"을 표시한다.** 표시만 하고 막지 않는다 | 읽기 API 하나로 되고, 충돌을 머지 시도 전에 담당자가 알 수 있다 |

## 3. 흐름

```
[작업 A 담당]                         [BEEZ 서버]                         [GitHub]
 구현 → push(branch)
 report_task_state(COMPLETED,
   code_ref.branch) ───────────────▶ 완료 보고에 브랜치 저장
                                     브랜치로 PR 찾기 ───────────────▶ GET pulls?head=…&state=all
 (사람 또는 에이전트가 PR 열고 리뷰·머지)
                                                                         PR 머지됨
[작업 B 담당 — A가 BLOCKS 선행]
 git fetch && git pull
 report_task_state(START,
   base_sha) ──────────────────────▶ G3: A가 COMPLETED?
                                         A의 PR이 기준 브랜치에 머지? ─▶ GET pulls/{n}
                                         base_sha ⊇ A의 머지 커밋? ───▶ GET compare/{merge}...{base}
                                     통과 → IN_PROGRESS
                                     실패 → 409 + 무엇을 pull할지
```

## 4. 저장소 연결 (프로젝트)

- 팀장(`LEADER`)이 저장소 URL·기준 브랜치·토큰을 등록·수정·해제한다. 멤버는 URL과 기준 브랜치만 본다. 토큰은 "설정됨/없음"과 설정 시각만 보인다.
- 등록할 때 GitHub에 한 번 물어 저장소가 보이는지, 기준 브랜치가 있는지 확인한다. 실패하면 저장하지 않는다.
- 토큰 권한은 `Metadata: Read`, `Contents: Read`, `Pull requests: Read`면 충분하다. 쓰기 권한은 받지 않는다.
- 저장소를 바꾸면 그전에 연결된 PR은 그대로 두고, 새 확인부터 새 저장소를 쓴다.

API는 PRJ-A04(프로젝트 정보 수정)를 넓히지 않고 따로 둔다. 토큰을 다루는 요청을 일반 정보 수정과 섞지 않기 위해서다.

| 안 | 메서드·경로 | 권한 |
| --- | --- | --- |
| 조회 | GET `/api/projects/{id}/repository` | MEMBER |
| 등록·수정 | PUT `/api/projects/{id}/repository` | LEADER |
| 해제 | DELETE `/api/projects/{id}/repository` | LEADER |

## 5. 작업 ↔ PR 연결

### 5.1 완료 때 브랜치 보고

`report_task_state`·TSK-A07의 `COMPLETED`에 `code_ref`를 더한다. 저장소가 연결된 프로젝트에서는 둘 중 하나가 필수다.

| 필드 | 설명 |
| --- | --- |
| `code_ref.branch` | 이 작업 코드를 push한 브랜치 이름 |
| `code_ref.no_code_reason` | 코드 변경이 없는 작업(조사·문서만 등)의 사유. 이 경우 선행 작업이어도 머지 확인을 건너뛴다 |

- 둘 다 없거나 둘 다 있으면 `400 validation_failed`.
- 완료 때 브랜치가 아직 원격에 없어도 받는다. PR은 사람이 나중에 열 수 있기 때문이다. 이 사실은 응답 `notice`로 알린다.
- 값은 불변인 `task_completion_report`에 함께 저장한다. delta 완료(S3)도 같다.

### 5.2 PR 찾기

- 서버는 보고된 브랜치로 PR을 찾는다(`GET /repos/{o}/{r}/pulls?head={owner}:{branch}&state=all`). 기준 브랜치(`base.ref`)로 들어가는 PR만 인정한다.
- 같은 브랜치에 PR이 여럿이면 머지된 것을 먼저 고르고, 없으면 가장 최근 것을 고른다.
- 자동으로 못 찾는 경우(fork 등)를 위해 담당자나 팀장이 웹에서 PR URL을 직접 붙일 수 있다. 직접 붙인 연결은 자동 찾기가 덮어쓰지 않는다.
- 1작업 1PR로 본다. 두 번째 PR을 붙이면 앞의 연결을 바꾼다(이력은 활동 기록에 남긴다).

## 6. 시작 게이트 G3 확장

START·RESUME에 `base_sha`를 더한다. **기준 브랜치를 pull한 뒤 작업을 시작한 커밋**이다. 로컬 커밋이 섞인 브랜치에서 시작한다면 `git fetch` 뒤 `git merge-base HEAD origin/<기준 브랜치>` 값을 보낸다. 이 값은 항상 GitHub에 있다.

저장소가 연결된 프로젝트에서 선행 작업(`BLOCKS`)마다 차례로 본다. 실패는 지금처럼 모아서 한 번에 돌려준다.

| 순서 | 검사 | 실패 code | 메시지 예 |
| --- | --- | --- | --- |
| G3-a | 선행 작업이 `COMPLETED` (지금과 같음) | `409 dependency_not_completed` | 선행 작업 미완료: T-12 |
| G3-b | 선행 작업의 PR이 기준 브랜치에 머지됨. `no_code_reason` 완료면 건너뜀 | `409 dependency_not_merged` | T-12의 PR #34가 아직 머지되지 않았습니다 / T-12에 연결된 PR이 없습니다 |
| G3-c | `base_sha`가 선행 작업의 머지 커밋을 포함함 — `compare/{merge_sha}...{base_sha}`의 `status`가 `ahead` 또는 `identical` | `409 base_missing_dependency` | 시작 커밋에 T-12의 머지 커밋 abc1234가 없습니다. git pull 후 다시 시작하세요 |

추가 규칙:
- `base_sha`가 없으면 `400 validation_failed`. GitHub에 없는 커밋이면(compare가 404) `409 base_not_found`로 응답하고, merge-base를 보내라고 안내한다.
- GitHub 조회가 실패하면(네트워크·한도·토큰 만료) `503 repository_unavailable`. 메시지에 원인 분류(토큰 만료 등)를 넣어 팀장이 고칠 수 있게 한다.
- squash·rebase 머지에서도 `merge_commit_sha`는 기준 브랜치 위의 커밋이므로 같은 검사가 성립한다.
- 머지 결과(머지 여부·`merge_commit_sha`·`merged_at`)는 머지된 뒤에는 바뀌지 않으므로 저장하고 다시 묻지 않는다. 머지 전인 PR만 매번 묻는다. compare 결과도 (머지 커밋, base) 쌍마다 바뀌지 않으므로 서버 메모리에 캐시한다.
- G3-b에서 막힌 에이전트는 기존 `WAITING(DEPENDENCY)`로 기다리다가, pull한 뒤 새 `base_sha`로 RESUME한다. 새 상태는 만들지 않는다.
- START·RESUME 활동 기록 details에 `base_sha`를 남긴다(누가 어느 커밋에서 시작했는지 추적).

**선행 관계가 없는 작업(D3)**: `base_sha`가 기준 브랜치 끝보다 뒤처졌으면 막지 않고 응답 `notices`에 "기준 브랜치보다 N커밋 뒤처져 있습니다"를 싣는다. 이 경고를 위한 조회는 실패해도(`base_sha`가 GitHub에 없음, GitHub 장애) 시작을 막지 않는다 — D6의 fail closed는 G3-b·c에만 적용한다.

**RESUME도 같다.** 게이트는 START·RESUME 둘 다에 걸리므로 저장소가 연결된 프로젝트에서는 RESUME에도 `base_sha`가 필요하다. 인계(S5)를 받은 새 담당자도 자기 커밋으로 RESUME한다.

## 7. 완료 뒤 코드 변경 — 리뷰 반영과 머지 충돌 해소

충돌 해소는 git으로 직접 한다(BEEZ는 코드를 고치지 않는다). 다만 **그 과정은 BEEZ를 거친다.** 거치지 않으면 (1) 완료 뒤 코드가 바뀐 사실이 기록에 남지 않고, (2) 상대 작업의 승인 계약을 덮어쓰는 설계 충돌이 코드 수정으로 묻힌다.

### 7.1 전이 (D8)

지금 [04-task-lifecycle](04-task-lifecycle.md) §2.1 전이 표에서 `COMPLETED`는 `COMPLETED` 재전송(멱등) 말고 모두 ✗다. 여기에 하나를 연다.

| 현재 | 이벤트 | 조건 | 결과 |
| --- | --- | --- | --- |
| COMPLETED | RESUME | 저장소가 연결된 프로젝트이고, 이 작업이 `code_ref.branch`로 완료했고, 연결된 PR이 **아직 머지되지 않음**(PR이 없거나 OPEN·CLOSED) | 시작 게이트(G1~G4, G3 확장 포함) 통과 시 **IN_PROGRESS** |

- 그 밖의 경우는 지금처럼 `400 invalid_transition`이다. PR이 이미 머지됐으면 `409 already_merged`로 따로 알리고, 이어서 고칠 일은 새 작업으로 만들라고 안내한다. `no_code_reason`으로 완료한 작업과 저장소가 연결되지 않은 프로젝트(D5)는 열지 않는다.
- 옛 완료 보고는 불변 이력으로 남는다. 다시 `COMPLETED`하면 보고가 하나 더 쌓이고 `completed_at`이 새로 찍힌다. 다시 완료할 때 `code_ref.branch`도 다시 보낸다.
- RESUME은 시작 게이트를 지난다. 그사이 설계가 발행돼 시작 때 검증이 STALE이 됐으면 G1에서 막히고 계획을 고쳐야 한다 — 의도된 동작이다. 새 `base_sha`로 선행 작업 머지도 다시 확인한다.
- 이 작업을 선행으로 둔 작업은 어차피 머지 게이트(G3-b)로 막혀 있으므로, 다시 열어도 아래쪽 작업에 새로 생기는 영향은 없다.
- 다시 `COMPLETED`할 때 계획 대비 달라진 점이 있으면 `delta`로 보고한다. 기존 S3·S4 흐름(DELTA 검증 → 완료)이 그대로 돈다.

### 7.2 머지 충돌 해소 절차

| 단계 | 할 일 | BEEZ에서 |
| --- | --- | --- |
| 1. 누가 | 나중에 머지하는 PR의 작업 담당자가 푼다. 본인의 에이전트에게 맡겨도 된다 | 작업 화면의 "충돌 있음" 표시(D9)로 안다 |
| 2. 다시 열기 | 기준 브랜치를 받아 작업 브랜치에 합친다(merge·rebase는 팀 컨벤션). 합친 뒤의 merge-base로 | `RESUME` + 새 `base_sha` |
| 3. 상대 의도 확인 | 충돌 난 파일을 `git log`로 따라가 상대 PR 번호를 찾는다 | `get_task`에 PR 번호를 넣어 상대 작업의 계획·범위를 읽는다(§8) |
| 4. 충돌 가르기 | 아래 표 | 아래 표 |
| 5. 다시 완료 | push → `COMPLETED` → PR 머지 | 머지되면 이 작업을 선행으로 둔 작업의 게이트가 열린다 |

| 종류 | 판단 기준 | 처리 |
| --- | --- | --- |
| 코드 충돌 | 두 작업의 의도를 모두 살려 풀 수 있고, 내 계획 범위(바꾸기로 한 설계 항목) 밖을 건드리지 않는다 | 풀고 delta 없이 `COMPLETED`. 해소 내용은 `write_context`로 남긴다 |
| 설계 충돌 | 풀려면 상대 작업의 승인 계약이나 내 계획에 없던 설계 항목을 바꿔야 한다 | **코드로 혼자 풀지 않는다.** `propose_design_change` → 팀장 승인·발행 → 계획 수정·재검증 → 진행 |

### 7.3 한계와 예방

- **delta는 자기 신고다.** 설계 충돌을 코드 충돌로 말하고 넘기면 서버는 잡지 못한다. 결정적으로 잡으려면 PR 코드를 승인 계약과 대조하는 G4(oasdiff)가 필요하고, 이건 확장으로 남는다(§9).
- **예방**: 같은 설계 항목을 동시에 `CHANGES`로 잡는 작업은 시작 게이트 G4가 이미 막는다. 그래서 남는 충돌은 대부분 같은 파일을 건드린 코드 충돌일 것으로 본다.
- `mergeable_state`는 GitHub가 비동기로 계산해 처음 조회에서는 `unknown`일 수 있다. 그때는 "확인 중"으로 표시하고 다음 조회에서 다시 읽는다.

## 8. 에이전트에게 보여 줄 것

- `get_task`(MCP-16)·TSK-A03 응답 [TaskDetail](../api/00-common.md#schema-taskdetail)에 다음을 더한다.
  - `repository`: `{url, base_branch}`. 연결 안 됐으면 `null`
  - `dependencies[]`마다 `code`: `{pr_url, pr_number, merged, merge_commit_sha, no_code_reason}`
  - 이 작업 자신의 `code`(완료 뒤 PR 연결 상태). `mergeable_state`(`clean`·`dirty`(충돌)·`unknown` 등 GitHub 값)와 그 확인 시각을 함께 싣는다
- `get_task`는 `task_id` 대신 `pr_number`도 받는다(둘 중 하나). 충돌 해소 때 상대 PR에서 상대 작업을 찾는 데 쓴다(§7.2). 같은 프로젝트에 연결된 PR만 찾고, 없으면 기존과 같은 `404 not_found`.
- 에이전트는 시작 전에 무엇을 pull해야 하는지 이 값으로 안다. [연결 매뉴얼](../guides/agent-connection.md)에 두 절차를 넣는다: "시작 전 fetch·pull → merge-base를 `base_sha`로", "완료 뒤 코드를 다시 고치면(리뷰 반영·충돌 해소) RESUME부터, 설계 충돌은 변경 제안으로".
- 웹: 프로젝트 설정에 저장소 칸, 작업 상세·목록에 PR·머지 표시("머지 대기"·"충돌 있음" 배지), PR URL 직접 붙이기.

## 9. 하지 않는 것 (확장으로 남김)

- 웹훅(GIT-03), PR 체크·코멘트(TRC-08), PR 코드와 승인 계약 대조(G4 oasdiff)
- "선행 PR이 머지됐습니다" 알림 — 웹훅 또는 주기 조회가 필요하다
- 머지 뒤 revert 판정(D7)
- GitHub App 설치형 인증(D4), GitHub 외 저장소(GitLab 등)

## 10. 저장 (안)

| 표 | 칸 | 비고 |
| --- | --- | --- |
| `project_repository` (새) | `project_id` PK·FK, `owner`, `name`, `base_branch`, `token_ciphertext` NULL, `token_set_at`, `updated_by`, `updated_at` | 프로젝트당 0~1행. 행이 없으면 D5대로 지금과 같다 |
| `task_completion_report` | `code_branch` NULL, `no_code_reason` NULL 추가 | 불변 표라 완료 때 함께 넣는다. 저장소 연결 프로젝트에서는 둘 중 하나만 차 있어야 한다(서비스 검사) |
| `task_code_link` (새) | `task_id` PK·FK, `pr_number`, `pr_url`, `head_branch`, `base_ref`, `state`(OPEN·CLOSED·MERGED), `merge_commit_sha`, `merged_at`, `source`(AUTO·MANUAL), `linked_by`, `checked_at`, `mergeable_state` NULL, `mergeable_checked_at` NULL | 바뀌는 사실이라 완료 보고와 분리. MERGED가 되면 다시 묻지 않는다. `pr_number`로 찾을 수 있게 색인하고, 프로젝트 조건은 `task`와 조인해 건다(§8 `get_task`) |

토큰 암호화: 지금 코드에는 암호화 유틸이 없다(검색 확인). 서버 환경변수의 키로 AES-GCM 암복호화하는 작은 컴포넌트를 새로 둔다. 키는 기존 비밀값처럼 서버 env 파일로 관리하고, 키 교체 절차를 런북에 적는다.

## 11. 작업 나누기 (안)

1티켓 1PR 원칙에 맞춰 나눈다. 순서대로 의존한다.

| # | 범위 | 주 담당 후보 |
| --- | --- | --- |
| 1 | 저장소 연결 API·토큰 암호화·`project_repository` | BE |
| 2 | `COMPLETED`의 `code_ref`·PR 찾기·`task_code_link`·`mergeable_state` 조회·PR 직접 연결 API | BE |
| 3 | G3-b·c, `base_sha`, 뒤처짐 경고, `get_task`·TaskDetail 확장(`pr_number` 조회 포함) | BE |
| 4 | 머지 전 `COMPLETED` → `RESUME` 전이(D8), `already_merged` | BE |
| 5 | 프로젝트 설정 저장소 칸, 작업 PR·머지·충돌 표시, PR 직접 붙이기 | FE |
| 6 | 명세(TSK-A07·MCP-12·MCP-16·TaskDetail·오류 표)·[04-task-lifecycle](04-task-lifecycle.md) 전이 표·연결 매뉴얼·런북 키 관리 | 문서 |

## 12. 변경 이력

- v0.2 (2026-11-13) 완료 뒤 코드 변경(리뷰 반영·머지 충돌 해소) 절차 추가(§7). D1을 "새 상태 없음, 전이 하나 추가"로 고치고 D8(머지 전 완료 작업 RESUME)·D9(충돌 표시) 추가, `get_task`의 `pr_number` 조회, 작업 나누기에 4번 추가.
- v0.1 (2026-11-13) 초안. 전진 제안, 팀 합의 전.
