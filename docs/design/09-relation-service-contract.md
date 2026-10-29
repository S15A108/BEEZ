# 설계 의존관계 저장 서비스 계약

작성: 변준성 · Jira: S15P31A108-167 · 대상: 오서현(최초 발행 전 관계 초안 검토·변경 제안 승인·발행 진입점), 변경 영향 후보 저장 담당

설계 항목 사이 의존관계(`A DEPENDS_ON P`)를 확정·재확인·해제하고 이력과 관계 집합 리비전을 남기는 서비스의 입구·입력·출력·오류를 적는다. 진입점(언제 무엇을 넘길지)은 오서현 담당이고, 이 서비스는 받은 변경을 검사해 저장한다. 담당 분담은 [#40 검토안](development-context-knowledge-graph-review-S15P31A108-110.md) 3.4·3.7절 합의를 따른다. 표 구조는 [ERD V9](03-erd.md#design-relation-history).

## 1. 입구

| 항목 | 규칙 |
| --- | --- |
| 메서드 | `RelationStorageService.apply(CallerContext caller, long projectId, RelationChangeRequest request)` → `RelationChangeResult` |
| 트랜잭션 | **호출자의 트랜잭션 안에서만** (`MANDATORY`). 밖에서 부르면 바로 실패한다. 발행이 롤백되면 관계·이력·리비전도 함께 롤백된다 |
| 호출 횟수 | 발행·승인 한 번에 **한 번** 부른다. 리비전은 호출마다 최대 1 오른다 |
| 잠금 | 프로젝트 행 `FOR UPDATE` → 관계 행 `FOR UPDATE` → 리비전 행. 발행이 이미 프로젝트 행을 잠갔다면 그대로 통과한다 |
| 권한 | 확정·유지·해제·재확인 표시가 있으면 **사람 팀장**(잠금 뒤 DB 에서 역할을 다시 확인), 후보 추가·거절만 있으면 프로젝트 멤버 |

## 2. 입력 `RelationChangeRequest`

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `baselineId` | 확정·유지·재확인 표시가 있으면 Y. 해제·후보 추가·후보 거절만이면 N | 유효 기준. 주면 해제 이력에도 그 기준의 버전을 남긴다. 이 프로젝트의 **가장 최근 기준**이어야 한다 — 현재 기준, 또는 발행 중이면 이번 발행에서 만든 기준(아직 현재 기준으로 바꾸기 전이어도 됨). 지난 기준은 400. 이 기준의 항목 버전을 이력에 남긴다 |
| `changeProposalId` | N | 관련 변경 제안. 최초 발행은 비운다 |
| `recheckItemIds` | N | 이번 발행으로 내용이 바뀌거나 빠진 항목. 이 항목이 끝에 걸린 **확정** 관계를 재확인 필요로 표시한다(자동 해제하지 않음). 이력에는 양 끝 항목의 유효 기준 버전을 기준에 있을 때 남긴다 |
| `recheckReason` | N | 재확인 표시 사유 (2,000자 이하) |
| `commands` | N | `RelationCommand(action, fromItemId, toItemId, reason)` 목록. 순서대로 적용 |

처리 순서는 ① `recheckItemIds` 재확인 표시 → ② `commands`. 그래서 같은 호출에서 재확인 필요가 된 관계를 바로 `KEEP`·`RELEASE` 할 수 있다.

| `action` | 할 수 있는 상태 | 결과 | 확정 집합 변경 |
| --- | --- | --- | --- |
| `CONFIRM` | 없음·해제·후보·거절 | EXPLICIT / CONFIRMED, 확인자·시각 갱신. 양 끝이 유효 기준에 있어야 함 | Y |
| `KEEP` | 재확인 필요 | CONFIRMED, 확인자·시각 갱신. 양 끝이 유효 기준에 있어야 함 | Y |
| `RELEASE` | 확정·재확인 필요 | RELEASED (마지막 확인자·시각은 남김) | Y |
| `ADD_CANDIDATE` | 없음·거절 (팀장이 해제한 관계는 후보로 되돌리지 않음. 다시 쓰려면 `CONFIRM`) | AI_CANDIDATE / CANDIDATE, 확인자·시각 비움 | N |
| `REJECT_CANDIDATE` | 후보 | REJECTED, 확인자·시각 비움 | N |

## 3. 출력 `RelationChangeResult`

| 필드 | 설명 |
| --- | --- |
| `relationSetRevision` | 처리 뒤 관계 집합 리비전. 확정 집합이 바뀌었으면 1 오른 값, 아니면 지금 값(없으면 0) |
| `changes` | 바뀐 쌍 (이력 ID, from, to, action, 변경 뒤 origin·status). 재확인 표시 → 명령 순 |

## 4. 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 바꿀 것이 없음, 필요한 기준 없음, 기준이 가장 최근 기준이 아님, 변경 제안·항목이 이 프로젝트 것이 아님, 같은 항목끼리, 한 호출에서 같은 쌍 두 번, 근거 없음·2,000자(코드 포인트) 초과, 확정·유지 항목이 유효 기준에 없음 |
| 400 | `invalid_transition` | 지금 상태에서 할 수 없는 변경 (위 표) |
| 401·403·404 | — | `ProjectAccessChecker` 규칙. 팀장이 아니면 403, 비멤버 404 |

오류가 나면 이 호출이 바꾼 것은 하나도 남지 않는다(호출자 트랜잭션과 함께 롤백). 이 서비스는 호출자 트랜잭션에 들어가 실행되므로, 예외가 나면 호출자 트랜잭션 전체가 "롤백만 가능" 상태가 된다. 호출자는 이 오류를 그대로 응답하거나 자기 API 의 오류로 **바꿔 던질 수는 있지만, 삼키고 계속 진행해 커밋할 수는 없다**(커밋 시 UnexpectedRollbackException). 바꿀 때는 그 API 오류표에 있는 code 만 쓴다.

## 5. 관계 집합 리비전

- 범위: 프로젝트 단위, 확정 관계 집합(CONFIRMED·NEEDS_RECHECK 의 구성과 상태). 후보 추가·거절과 관계 초안은 넣지 않는다. 작업 계획 참조(`plan_revision_id`)와 설계 버전(`baseline_id`)은 따로 비교한다.
- 갱신: 확정 집합을 바꾸는 호출마다 그 트랜잭션에서 1 증가. 행 없음 = 0 (관계 변경이 한 번도 없음).
- V9 이전 행: 당시 기준·항목 버전·근거를 복원할 수 없어 확인자가 있어도 확정으로 보지 않고 모두 재확인 필요(AI 후보는 후보)로 둔다. 그런 프로젝트의 리비전은 1 에서 시작한다. 팀장이 유지(`KEEP`)하면 그때 기준·버전·근거가 이력에 남는다.
- 읽기: `DesignDependencyQueryService.currentRevision(projectId)` (권한 확인을 끝낸 호출자가 부른다). REST 는 TRC-A01 `relation_set_revision`.
- 최신성: 후보에 저장한 리비전이 `null`이거나 현재 값을 읽을 수 없으면 **최신성 미확인**, 같으면 최신, 다르면 `STALE`(재분석 대기). 미확인·STALE 은 최신으로도 영향 0건으로도 보지 않는다.

## 6. 진입점별 예

| 진입점 | 넘길 값 |
| --- | --- |
| 최초 기준 발행 (관계 초안 포함) | 기준 항목을 넣은 뒤 `baselineId` = 새 기준, `commands` = 초안 관계마다 `CONFIRM`(근거 = 초안 검토 근거) |
| 변경 제안 승인·발행 | `baselineId` = 새 기준, `changeProposalId`, `recheckItemIds` = UPDATE·RETIRE 한 항목, `commands` = 제안에 담긴 관계 확정·해제 |
| 재확인 처리 (팀장 유지·해제) | `baselineId` = 현재 기준, `commands` = `KEEP`·`RELEASE`. 이 화면·API 는 아직 없다(별도 합의) |

## 7. 아직 없는 것

- 관계를 바꾸는 REST API 는 두지 않는다. 확정 관계 변경은 변경 제안으로 처리한다 (TRC-A01 처리 규칙).
- 관계 초안(발행 전 검토 중인 관계)의 보관은 진입점 쪽 범위다. 이 서비스는 확정·후보 상태만 저장한다.
- AI 후보를 만드는 기능은 아직 없다. `ADD_CANDIDATE` 는 그 기능이 생기면 쓴다.
- 규칙 후보 계산(정책 ← API ← 작업)은 별도 작업이다. 이 서비스의 확정 관계와 리비전을 입력으로 쓴다.
