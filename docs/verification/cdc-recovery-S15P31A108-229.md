# CDC 통합·장애 복구 검증 — S15P31A108-229

검증일: 2026-11-10 (KST). 대상: PR #245에 `dev` `f4f80049`를 반영한 코드.
기능·전체 회귀·실제 CDC·worker 강제 종료 복구 및 12종 실제 알림의 발생·해소 수신을 검증했다.
10:50 사용자 요청으로 추가 장애 시험을 종료했다. 마지막 선택 항목인 Connector 삭제·재등록은
실행하지 않았으며, `CdcConnectorMissing`의 실제 발생·해소를 완료로 기록하지 않는다.

## 검증 범위

Jira 229 본문과 11-06 인수 댓글을 기준으로 승인·발행→접수→Debezium→Kafka→Consumer→분석·조회·화면,
초기 적재, 중복·순서, 중단·재시작·재처리, 권한·민감정보 및 실제 Mattermost 발생·해소 수신을 확인한다.
서버의 Kafka·Connect·DB는 중지하지 않는다. 장애 주입 대상은 이번 실행이 만든 로컬 Compose 프로젝트뿐이다.

| 실행 | 실제 구성요소 | 시험 대역·범위 |
|---|---|---|
| 전체 백엔드 회귀 | Spring, PostgreSQL/Testcontainers, 실제 HTTP/세션 | 테스트별 AI 대역 및 opt-in 제외 항목은 별도 집계 |
| `recovery-229` | 실제 발행 서비스·접수 테이블, 같은 PostgreSQL, Debezium, Kafka, 실제 Consumer·dispatcher | AI 응답은 결정적 대역. 외부 모델 정확히 한 번 실행을 주장하지 않음 |
| `ui-229` | Chromium, 실제 프론트, 실제 백엔드 API/세션 | 응답 가로채기·mock API 사용 안 함. 위 격리 시험이 발행한 데이터 조회 |
| `monitoring-229` | 실제 backend jar·DB 집계·Kafka·Connect·Prometheus·Alertmanager·기존 Mattermost 수신처 | 실패/차단/만료 상태의 알림 시험 일부는 SQL fixture. 실제 프로세스 장애 시험과 구분 |

모니터링의 표현식·`for`·`keep_firing_for`·그룹/재통지 주기는 운영 설정 그대로다.
알림 제목의 `[229 격리 검증]`과 환경 라벨만 시험 식별에 사용한다.
웹훅과 GMS 키는 저장소에 넣지 않는다.

## 전체 회귀 결과

| 검사 | 결과 |
|---|---|
| 백엔드 빠른 층 | 456개 중 454 통과, 선택 시험 2 건너뜀, 실패 0 |
| 백엔드 PostgreSQL 통합 층 | 992개 중 967 통과, 선택 시험 25 건너뜀, 실패 0 |
| 프론트 린트·포맷·빌드 | 통과 |
| 프론트 단위·컴포넌트 | 124개 파일, 1,002개 통과 |
| 프론트 기존 브라우저 회귀 | Chromium 34개 통과. 기존 suite는 예시 API 기반이며 실제 backend 연동 증거는 `ui-229`로 별도 기록 |
| AI 린트·포맷 | Ruff 검사 통과, 251개 파일 포맷 확인 |
| AI 전체 회귀 | Linux Python 3.12에서 1,364 통과·Docker CLI 필요 3 제외. 해당 3개는 Windows에서 별도 통과: 고유 시험 1,367개 모두 확인 |
| Prometheus 운영 규칙 회귀 | 저장소 고정 이미지의 `promtool test rules beez_test.yml`: SUCCESS |

선택 시험을 건너뛴 전체 suite의 초록불만으로 Kafka·FastAPI·실제 모델 검증을 대신하지 않는다.
해당 opt-in 실행 결과는 아래 별도 기록한다.
AI의 Windows 전체 실행은 `asyncio` 소켓 생성 시 `WinError 10014`로 실패했다.
제품 코드를 바꾸지 않고 읽기 전용 소스를 Linux 컨테이너에 마운트해 전체를 재실행했다
([환경·집계](evidence/229-ai-regression.json)).

## 선택 통합 실행

| 실행 | 결과·증명 범위 |
|---|---|
| Spring/PostgreSQL → 별도 FastAPI, 결정적 provider | 6개 중 5개 통과, 실제 GMS 전용 1개 제외. chunking·embedding HTTP, 변경 영향과 사용량/timeout 경계 확인 |
| 실제 GMS 발행 후 분석 | 별도 opt-in 1개 통과, 건너뜀 0. `lifecycle=COMPLETED`, `usage_status=REPORTED`, 실제 모델 `gpt-6-luna`, 입력 1,217 / 출력 315 tokens |
| 같은 DB의 실제 CDC와 worker 복구 | 순서가 있는 인수 시험 1개, 아래 9개 검증 모두 통과. 건너뜀 0 |
| 별도 PostgreSQL 순서·상태 검증 | opt-in 1개 통과. 실패/누락 선행 건의 후속 처리 차단과 완료 후 해제, 기대된 미평가와 비정상 미평가 구분, 오래된 평가 배제·접근 범위 확인 |
| 기존 Kafka 통합 시험 | 별도 opt-in 1개 통과, 건너뜀 0. 실제 Kafka 중복 신호·복구 스캔 검증; 생산 레코드와 AI 응답은 fixture |
| 디스패처 집중 회귀 | 4개 통과. 실패 시 acknowledge 금지, 실행 중 중복 억제, 실패 재시도, 큐 포화 후 복구 |
| 실제 프론트 → 실제 API | 접근 경계·저장 결과 조회·화면·새로고침·발행 전 분석 구분 통과. 브라우저 예외 0 |

실제 GMS 실행의 평가 상태는 `INSUFFICIENT_EVIDENCE`, 오류 코드는
`publication_relations_unavailable`였다. 현재 발행 후 snapshot은 관계 연결의 완전성을
보장하지 않으므로 기존 coordinator가 근거 부족으로 보호하는 결과다.
모델 호출·사용량·결과 저장이 완료됐다는 증거이며, 평가 `COMPLETED` 또는 확정된 영향 0건으로 기록하지 않는다.

실제 CDC 시험은 하나의 격리 PostgreSQL을 Spring과 Debezium이 공유한다.
부모 시험의 복구 스캔만 한 시간 지연시켜 캡처가 실패해도 fallback으로 우연히 통과하지 않도록 했다.
강제 종료 시험의 별도 애플리케이션 JVM은 실제 복구 스캔을 사용한다.

| 실제 CDC 검증 | 최종 관측 |
|---|---|
| 최소 권한 | 비슈퍼유저 replication 계정과 publication 모두 `id,status`만 허용. `project_id`, `account.email` 조회는 SQLSTATE 42501로 거절 |
| 초기 경계 | 실제 발행 transaction이 만든 접수의 초기 snapshot `op=r` → Consumer → 결과 저장 |
| 실시간 경로 | 이후 실제 발행 commit의 `op=c` → Consumer → 결과 저장 |
| Consumer 중단·재시작 | 중단 중 새 발행은 PENDING, attempt 없음, lag 3. 같은 group 재시작 후 완료 |
| 같은 레코드 재처리 | 실제 committed offset을 0으로 되돌려 동일 topic/partition/offset 재전달. 완료된 3건의 attempt와 AI 호출은 각 3회로 유지 |
| rollback | 승인·발행 rollback 후 접수와 Kafka capture 없음. 이후 committed sentinel로 스트림 진행 확인 |
| 민감정보·기존 경로 | 11개 이벤트 `after` 필드는 정확히 `id,status`; 합성 private marker와 계정 정보 미포함. legacy inbox 0건 |
| 실제 worker 강제 종료 | 별도 JVM이 HTTP AI 호출에 진입하고 RUNNING/attempt 1인 상태에서 강제 종료. 다른 JVM이 자연 lease 만료 후 attempt 2로 회수하고 결과 1건 저장. HTTP 호출 2회 |
| 화면 연결 | 위 강제 종료 후 저장한 결과를 실제 프론트에서 조회·새로고침. `근거 확인 필요`, 처리 시도 2회, 발행 전 분석과 별도 표시 |

강제 종료 시험의 lease는 SQL로 바꾸지 않았다. 시험의 read timeout 5초 + connect timeout 1초에
기존 여유 60초를 더한 66초를 실제 기다렸다. 저장 결과의 중복 방지와 외부 모델 호출의 exactly-once는 구분한다.
실행 결과는 [구조화 증거](../../infra/cdc-poc/recovery-229/evidence/results.json),
화면은 [실제 화면 캡처](../../infra/cdc-poc/recovery-229/evidence/ui/published-analysis-live.png)와
[UI 검사 결과](../../infra/cdc-poc/recovery-229/evidence/ui/ui-evidence.json)를 참고한다.
역순·누락 선행 건의 차단 근거는 [PostgreSQL 순서 시험](../../infra/cdc-poc/recovery-229/evidence/postgres-ordering-regression.json)과
기존 repository 통합 시험이다. Debezium 스트림 자체를 인위적으로 재정렬한 시험으로 표현하지 않는다.

## 재현 명령

저장소 루트 기준이며 Docker Desktop, JDK 21, frontend 의존성·Chromium이 필요하다.
외부 provider의 키와 Mattermost webhook은 별도 비공개 환경/파일로 전달한다.

```powershell
cd backend
.\gradlew.bat test integrationTest --console=plain
cd ../frontend
npm ci
npm run check
$env:BEEZ_E2E_PORT = '5199'
npm run test:e2e
cd ..
$env:BEEZ_229_UI = 'true'
python infra/cdc-poc/recovery-229/run.py
```

실제 캡처·강제 종료·화면의 상세 절차는 [recovery runner](../../infra/cdc-poc/recovery-229/README.md),
알림 시험의 기동·장애·정리는 [monitoring runner](../../infra/cdc-poc/monitoring-229/README.md)를 따른다.
원본 로그 대신 자격증명이 없는 구조화 결과와 실제 수신 메시지 링크를 증거로 보존한다.

## 권한·상태·개인정보 회귀 근거

- `PublishedAnalysisExecutionIntegrationTest.proposalDetailShowsAuthorizedPublicationStateAndDoesNotCallInsufficientEvidenceAnEmptySuccess`:
  프로젝트 멤버 조회 200, 외부인 404, `INSUFFICIENT_EVIDENCE`를 빈 성공으로 취급하지 않음.
- `missingPublishedIntakeIsUnknownRatherThanSuccessOrZeroImpact`: 접수 누락은 UNKNOWN.
- `accessRevokedAfterPublicationDoesNotInvokeModel`, `accessRevokedDuringModelCallPreservesReceivedUsageOnly`:
  권한 철회 후 모델 호출을 막고, 호출 도중 철회 시 결과를 버리되 수신한 사용량은 보존.
- `ImpactRunIntegrationTest.acceptsReplaysCompletesZeroCandidatesAndCreatesUniqueUsageKeys`,
  `failedAiPreservesExplicitAndInsufficientEvidenceIsDistinct`, `missingCandidatesAreNotEvaluatedAndDoNotCallAi`:
  완료된 0건·실패·근거 부족·미평가 구분.
- `ImpactContextQueryIntegrationTests.deniesProposalFromDifferentProject`, `keywordSearchNeverIncludesAnotherProjectsTasks`:
  다른 프로젝트 자료 배제.
- `ImpactMigrationIntegrationTest.upgradesV16WithHistoricalPublicationsWithoutSchedulingPastAiWork`:
  과거 발행 이관으로 새로운 AI 작업을 예약하지 않음.
- `PublishedAnalysisLogPrivacyTest`, `AnalysisIntakeKafkaTransportTest`:
  worker 로그 프로젝트 ID 가림, 레코드/DB 예외 내용 숨김, 실패 지표에 본문·식별자 라벨 없음.
- `DocumentApiIntegrationTest.indexesDurablySearchesOnlyCurrentBaselineAndPreservesApprovedSource`:
  문서 처리 COMPLETED/색인 READY, manifest 저장, 현재 기준에 속한 문서만 검색.
  현재 설계에서 문서 색인과 최초 관계 구축은 기존 경로를 유지하며, 접수 Consumer가 이를 다시 실행하지 않는다.
  문서 원문 CDC PoC와 발행 접수 CDC를 하나의 구현으로 취급하지 않는다([설계](../design/published-analysis-227.md)).

## 실제 알림 수신 기록

수신 채널: 기존 `A108_github 알림`. 아래 링크는 Mattermost 화면에서 직접 확인한 메시지다.
알림 전송 카운터만으로 채널 수신을 판정하지 않는다.

| 알림 | 발생 수신(KST) | 해소 수신 |
|---|---|---|
| CdcFailedIntakes | [09:59](https://meeting.ssafy.com/s15p30a1/pl/o5jsu5mcajrtxm64oatinudkta) | [10:29](https://meeting.ssafy.com/s15p30a1/pl/m9k7wgf7h3bufpw6szkksnnqjy) |
| CdcKafkaReceiveFailures | [09:59](https://meeting.ssafy.com/s15p30a1/pl/7yjg5p7netdc5bdr3m3ok8qbue) | [10:24](https://meeting.ssafy.com/s15p30a1/pl/emayb6w333df3ng9so6ertf5ir) |
| CdcConnectorNotRunning (task FAILED) | [10:04](https://meeting.ssafy.com/s15p30a1/pl/dc9uhn6zf3b8tgboir6ffx9f7r) | [10:19](https://meeting.ssafy.com/s15p30a1/pl/5agozbko9bbyics7wfhr6ht1gy) |
| CdcExpiredLeases | [10:04](https://meeting.ssafy.com/s15p30a1/pl/4qm74mus43b1bjjthpzu5iuqfo) | [10:29](https://meeting.ssafy.com/s15p30a1/pl/uuir71aqsjy7384chaf7fgrxur) |
| CdcConsumerLagStuck | [10:09](https://meeting.ssafy.com/s15p30a1/pl/u7k5c7yr3f88mjby1nzf4zsgrw) | [10:19](https://meeting.ssafy.com/s15p30a1/pl/wiek1bn98pbzdewj48h8ohh74c) |
| CdcBlockedProjects | [10:09](https://meeting.ssafy.com/s15p30a1/pl/toi5o3frqff67kjyx6ukz177co) | [10:29](https://meeting.ssafy.com/s15p30a1/pl/z7gokny6rtdf3fa9s7o6qrjpxe) |
| CdcSlotInactive | [10:09](https://meeting.ssafy.com/s15p30a1/pl/xkq3jzbq338e8q7i71u1xhspbw) | [10:19](https://meeting.ssafy.com/s15p30a1/pl/1q8zpwtke7bwtqdn9msfucty6h) |
| CdcPendingDelay | [10:15](https://meeting.ssafy.com/s15p30a1/pl/ytsw9en1878fmbyy7bqeo9wfeo) | [10:30](https://meeting.ssafy.com/s15p30a1/pl/7ra4i8up1irtpbajoeczrx8mdh) |
| CdcKafkaUnreachable (pause) | [10:22](https://meeting.ssafy.com/s15p30a1/pl/czxqdc7ng3ndzxtbm7drcegg1h) | [10:27](https://meeting.ssafy.com/s15p30a1/pl/bmzwrhzag7fypc1zhjspo8w77e) |
| CdcConsumerDown (pause) | [10:22](https://meeting.ssafy.com/s15p30a1/pl/puz8hd7yjty5fymcp8h97hbswe) | [10:27](https://meeting.ssafy.com/s15p30a1/pl/d1pxexma6jgipm7cacbkbyh39r) |
| CdcKafkaUnreachable (stop) | [10:30](https://meeting.ssafy.com/s15p30a1/pl/hbtcbjzgxtgfige5zs98nrrtqh) | [10:35](https://meeting.ssafy.com/s15p30a1/pl/swzmnefca7fo7xszohp5p4e1zh) |
| CdcConsumerDown (stop) | [10:30](https://meeting.ssafy.com/s15p30a1/pl/gmdgzzox8tn69qmfsfkkfqh4oy) | [10:35](https://meeting.ssafy.com/s15p30a1/pl/bojipuhus3yhbpey31ioy589br) |
| CdcConnectDown | [10:41](https://meeting.ssafy.com/s15p30a1/pl/huiu7zn8z3fzmce4mz1qfoy8ma) | [10:51](https://meeting.ssafy.com/s15p30a1/pl/awa4nhuw4jbfupo3danig35kkc) |
| CdcSnapshotFailing (실제 DB 집계 쿼리 실패) | [10:42](https://meeting.ssafy.com/s15p30a1/pl/rbw88h8yytbjignegpikerdb9c) | [10:47](https://meeting.ssafy.com/s15p30a1/pl/q1xa8kw7t7nk98z7heyn77b43o) |
| CdcSlotInactive (Connect 중단) | [10:46](https://meeting.ssafy.com/s15p30a1/pl/5fe5mqntt38eppzp5tn3mo8mqw) | [10:51](https://meeting.ssafy.com/s15p30a1/pl/if5thzu99pfc5efqdyun6rp86w) |

FAILED의 24시간 재통지는 운영 route의 `repeat_interval: 24h` 적용 경로를 확인한다.
24시간 동안 기다려 두 번째 재통지 수신을 실측한 것으로 표시하지 않는다.
`CdcSlotWalRetained`의 1GiB 초과 WAL 생성은 Jira 11-06 인수 댓글의 생략 권고를 따른다.
실제 대용량 WAL을 만들거나 수신했다고 주장하지 않으며, 저장소의 경계값·5분 지속 규칙 시험 통과로 기록한다.

## 실행·결함·판정 기록

기능 검증에서 제품 코드 변경이 필요한 회귀 실패는 발견되지 않았다. 검증 도구에서 확인한 문제는 다음과 같이 수정했다.

- 디스패처 재시도 시험은 두 번째 worker가 종료되는 순간 다음 스캔이 시작되면 fixture의 같은 행을
  세 번째로 실행할 수 있었다. 두 번째 worker를 검증 완료까지 latch로 유지하고 원래의 정확한 2회 조건을 보존했다.
- 실제 화면 시험은 예시 계정 11의 안내 완료 값을 사용해 새 계정의 안내창을 닫지 못했다.
  실제 안내 건너뛰기 버튼으로 진행하고, 상태 새로고침은 클릭 전에 등록한 정확한 GET 응답으로 판정한다.
- 캡처 runner는 현재 checkout의 JAR를 먼저 빌드한다. 준비·timeout·정리 실패 때 이전 성공 JSON을 남기지 않으며,
  최신 XML·실제 실행 수·실패/건너뜀 0을 확인한다. 제한시간 초과 시 자신이 만든 자식 프로세스만 정리한다.
- 알림 해소는 단순한 alert 부재로 판정하지 않는다. backend와 각 수집원, DB snapshot의 신선도,
  Connector/task/slot의 정상 상태를 함께 검사한다. 실제 채널의 수신 링크도 별도로 확인한다.
- 모니터링 장애 단계의 복원은 `finally`로 보장하며, 새 실행에서는 이전 이벤트 파일을 보관 이동해
  과거 성공 기록이 새 실행의 증거로 섞이지 않도록 했다.

Windows의 반복적인 `Bad address: listen`/`WinError 10014`는 테스트 JVM/event loop가 시작되기 전의
환경 오류로 구분했다. 백엔드 선택 시험은 독립 Gradle JVM으로 다시 실행했고,
AI 전체는 Linux에서 같은 소스로 다시 실행했다. 실패한 실행의 오래된 XML을 통과 결과로 재사용하지 않았다.

### 장애 시간표 (KST)

| 단계 | 실행·관측 |
|---|---|
| 초기 상태·오류 | 09:58:47 새 PENDING/FAILED/만료 RUNNING fixture와 실제 잘못된 Kafka 레코드·task FAILED 주입. 10:14:57 8종 firing 확인, 10:15:48 원인 제거 |
| Kafka pause | 10:15:48 정지, 10:16:12 backend UP, 10:16:49 새 HTTP 발행 완료(1회). 10:22:49 해제, 10:23:04 backend 재시작 없이 Consumer 재연결 |
| Kafka stop | 10:24:06 종료, 10:24:42 backend UP, 10:25:18 새 HTTP 발행 완료(1회). 10:31:19 시작, 10:32:19 backend 재시작 없이 재연결 |
| 브로커 복구 후 확인 | 10:35:52 두 발행의 실제 Kafka PENDING/RUNNING/COMPLETED 기록, 원래 Consumer group의 committed offset=log end, exporter lag=0, 각 발행 attempt=1 확인 |
| Connect·집계 실패 | 10:35:54 Connect 중단 및 격리 DB의 집계 대상 테이블 이름 변경. 10:41:54 두 알림 firing 확인 후 테이블 복원 |
| 슬롯 비활성·Connect 재시작 | 10:46:55 슬롯 10분 조건과 ConnectDown firing 확인 후 시작, 10:47:04 task RUNNING 확인 |
| 최종 복구 확인 | 10:50:52 추가 장애 주입을 중단한 뒤 backend UP, DB snapshot 신선도 4.43초, Kafka/Consumer UP, Connector/task RUNNING, slot active와 12종 알림 해소 조건 확인 |
| 실제 수신 확인 | 10:51 ConnectDown·SlotInactive 해소까지 채널에서 확인. 실행한 12종 모두 발생·해소 메시지 링크 확보 |
| 격리 환경 정리 | 10:53 시험 Compose 컨테이너·익명 볼륨과 자체 실행 프로세스 정리, 잔여 0개 확인 |

### 최종 범위와 미수행 항목

- 검증한 기능·회귀와 실제 장애 시나리오는 통과했다. 제품 코드 회귀 수정은 없고, 검증 코드의 불안정성·오판 경로를 보완했다.
- `CdcConnectorMissing` 삭제·재등록 실험은 **사용자의 조기 종료 요청으로 미수행**이다.
  관련 운영 규칙의 로딩·기준 시간 일치는 확인했지만, 이것을 실제 장애 주입·수신 증거로 대체하지 않는다.
- 24시간 재통지는 route 정책 확인만 수행했다. WAL 1GiB 초과 실험은 Jira 인수 댓글의 생략 권고에 따라 규칙 시험만 수행했다.
- 모니터링 [구조화 결과](../../infra/cdc-poc/monitoring-229/results.json)의 전체 선택 시나리오 완료 여부도
  미수행 항목을 반영한다. 생략한 실험까지 모두 통과한 것으로 표시하지 않는다.
