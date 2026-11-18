# 131 가상 개발 요청: 블라인드 검토

fixture SHA-256: `144c16074b4a04f303db53c82e79765c733ca5db49659f7876d0f3df585b4cd6`

이 자료는 사람이 별도로 판단하기 위한 미검토 자료다. 가상 사례이며 실제 BE 검색 결과가 아니다.
기존 기대 라벨·모의 선택·모델 출력은 이 자료에 포함하지 않았다. 검토가 끝날 때까지 이들을 열지 않는다.
요청에 직접 관련된 설계 버전 ID와 원문 ID를 별도 JSON 검토서에 적는다. 관계나 작업 참조만으로 추가되는 항목은 직접 관련으로 표시하지 않는다.
도구는 직접 관련 항목과 현재 작업 참조를 시작점으로 EXPLICIT·CONFIRMED DEPENDS_ON 관계를 1단계만 확장해 최종 기대 설계를 계산한다.
직접 관련 설계와 원문이 모두 없으면 no_match=true다. 작업 참조만 있어도 no_match=true일 수 있다.
판단할 수 없으면 uncertain=true로 표시하고 해당 사례의 세 판단 필드는 null로 둔다. 빈 목록은 판단 불가가 아니다.
입력의 설계와 관계는 인공 데이터다. 원문 근거는 표시된 파일에서 직접 확인한다.

## CASE-01

요청: Markdown 문서 업로드 생성 API를 구현해 주세요.

작업 참조: 없음

### 설계 후보 (인공)

- 항목 1000, 버전 1100: 최종 발행은 활성 프로젝트 리더만 수행한다.
- 항목 1001, 버전 1101: 단일 파일 업로드는 30 MB까지 허용하며 초과 시 413을 반환한다.
- 항목 1002, 버전 1102: POST /api/projects/{projectId}/documents는 Markdown 원문을 등록한다.
- 항목 1003, 버전 1103: GET /api/projects/{projectId}/documents는 목록을 조회한다.
- 항목 1004, 버전 1104: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

### 원문 후보

- e-plan: 문서의 최종 발행은 활성 프로젝트 리더만 수행한다. (출처: `ai/evaluations/data/plan.md:11`, SHA-256 `f8e265adf8d8be5ee57309613f5bbe84035e3aa6af5fd98c7dc4bc4bc1be1dd8`)
- e-limit: 단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다. (출처: `ai/evaluations/data/requirements.md:7`, SHA-256 `f34e4411607078fcc70f508daa1308195c7f873c1fa3842a278228a4965bc5e2`)
- e-post: POST /api/projects/{projectId}/documents (출처: `ai/evaluations/data/api-spec.md:5`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-get: GET은 문서 목록을 조회하며 새 문서를 생성하지 않는다. (출처: `ai/evaluations/data/api-spec.md:31`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-owner: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다. (출처: `ai/evaluations/data/data-design.md:23`, SHA-256 `3177765e5bfa43cceca0dccc942c0cc25b5a904f53c8187fdb02fb54bb486c1a`)

### 입력된 관계

- 1002/1102 → 1001/1101: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1001/1101 → 1000/1100: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1002/1102 → 1004/1104: DEPENDS_ON, EXPLICIT, NEEDS_RECHECK
- 1002/1102 → 1003/1103: DEPENDS_ON, AI_CANDIDATE, CONFIRMED

## CASE-02

요청: 문서 생성 시 업로드 크기 제한을 확인해 주세요.

작업 참조: 1004/1104

### 설계 후보 (인공)

- 항목 1000, 버전 1100: 최종 발행은 활성 프로젝트 리더만 수행한다.
- 항목 1001, 버전 1101: 단일 파일 업로드는 30 MB까지 허용하며 초과 시 413을 반환한다.
- 항목 1002, 버전 1102: POST /api/projects/{projectId}/documents는 Markdown 원문을 등록한다.
- 항목 1003, 버전 1103: GET /api/projects/{projectId}/documents는 목록을 조회한다.
- 항목 1004, 버전 1104: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

### 원문 후보

- e-plan: 문서의 최종 발행은 활성 프로젝트 리더만 수행한다. (출처: `ai/evaluations/data/plan.md:11`, SHA-256 `f8e265adf8d8be5ee57309613f5bbe84035e3aa6af5fd98c7dc4bc4bc1be1dd8`)
- e-limit: 단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다. (출처: `ai/evaluations/data/requirements.md:7`, SHA-256 `f34e4411607078fcc70f508daa1308195c7f873c1fa3842a278228a4965bc5e2`)
- e-post: POST /api/projects/{projectId}/documents (출처: `ai/evaluations/data/api-spec.md:5`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-get: GET은 문서 목록을 조회하며 새 문서를 생성하지 않는다. (출처: `ai/evaluations/data/api-spec.md:31`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-owner: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다. (출처: `ai/evaluations/data/data-design.md:23`, SHA-256 `3177765e5bfa43cceca0dccc942c0cc25b5a904f53c8187fdb02fb54bb486c1a`)

### 입력된 관계

- 1002/1102 → 1001/1101: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1001/1101 → 1000/1100: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1002/1102 → 1004/1104: DEPENDS_ON, EXPLICIT, NEEDS_RECHECK
- 1002/1102 → 1003/1103: DEPENDS_ON, AI_CANDIDATE, CONFIRMED

## CASE-03

요청: documents.owner_id 참조와 삭제 정책을 구현해 주세요.

작업 참조: 없음

### 설계 후보 (인공)

- 항목 1000, 버전 1100: 최종 발행은 활성 프로젝트 리더만 수행한다.
- 항목 1001, 버전 1101: 단일 파일 업로드는 30 MB까지 허용하며 초과 시 413을 반환한다.
- 항목 1002, 버전 1102: POST /api/projects/{projectId}/documents는 Markdown 원문을 등록한다.
- 항목 1003, 버전 1103: GET /api/projects/{projectId}/documents는 목록을 조회한다.
- 항목 1004, 버전 1104: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

### 원문 후보

- e-plan: 문서의 최종 발행은 활성 프로젝트 리더만 수행한다. (출처: `ai/evaluations/data/plan.md:11`, SHA-256 `f8e265adf8d8be5ee57309613f5bbe84035e3aa6af5fd98c7dc4bc4bc1be1dd8`)
- e-limit: 단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다. (출처: `ai/evaluations/data/requirements.md:7`, SHA-256 `f34e4411607078fcc70f508daa1308195c7f873c1fa3842a278228a4965bc5e2`)
- e-post: POST /api/projects/{projectId}/documents (출처: `ai/evaluations/data/api-spec.md:5`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-get: GET은 문서 목록을 조회하며 새 문서를 생성하지 않는다. (출처: `ai/evaluations/data/api-spec.md:31`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-owner: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다. (출처: `ai/evaluations/data/data-design.md:23`, SHA-256 `3177765e5bfa43cceca0dccc942c0cc25b5a904f53c8187fdb02fb54bb486c1a`)

### 입력된 관계

- 1002/1102 → 1001/1101: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1001/1101 → 1000/1100: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1002/1102 → 1004/1104: DEPENDS_ON, EXPLICIT, NEEDS_RECHECK
- 1002/1102 → 1003/1103: DEPENDS_ON, AI_CANDIDATE, CONFIRMED

## CASE-04

요청: 색인 실패 후 원문 보존 및 재시도 정책을 확인해 주세요.

이 사례의 원문 후보는 실측 당시 별도로 교체됐으며 다른 원문 후보는 포함되지 않았다. 일반적인 경쟁 검색으로 간주하지 않는다.

작업 참조: 없음

### 설계 후보 (인공)

- 항목 1000, 버전 1100: 최종 발행은 활성 프로젝트 리더만 수행한다.
- 항목 1001, 버전 1101: 단일 파일 업로드는 30 MB까지 허용하며 초과 시 413을 반환한다.
- 항목 1002, 버전 1102: POST /api/projects/{projectId}/documents는 Markdown 원문을 등록한다.
- 항목 1003, 버전 1103: GET /api/projects/{projectId}/documents는 목록을 조회한다.
- 항목 1004, 버전 1104: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

### 원문 후보

- e-retry: 색인 실패 시 원문 버전은 보존하고, 활성 프로젝트 멤버가 재시도할 수 있다. (출처: `ai/evaluations/data/requirements.md:13`, SHA-256 `f34e4411607078fcc70f508daa1308195c7f873c1fa3842a278228a4965bc5e2`)

### 입력된 관계

- 1002/1102 → 1001/1101: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1001/1101 → 1000/1100: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1002/1102 → 1004/1104: DEPENDS_ON, EXPLICIT, NEEDS_RECHECK
- 1002/1102 → 1003/1103: DEPENDS_ON, AI_CANDIDATE, CONFIRMED

## CASE-05

요청: 감사 로그 보관 기간은 며칠인가요?

작업 참조: 없음

### 설계 후보 (인공)

- 항목 1000, 버전 1100: 최종 발행은 활성 프로젝트 리더만 수행한다.
- 항목 1001, 버전 1101: 단일 파일 업로드는 30 MB까지 허용하며 초과 시 413을 반환한다.
- 항목 1002, 버전 1102: POST /api/projects/{projectId}/documents는 Markdown 원문을 등록한다.
- 항목 1003, 버전 1103: GET /api/projects/{projectId}/documents는 목록을 조회한다.
- 항목 1004, 버전 1104: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다.

### 원문 후보

- e-plan: 문서의 최종 발행은 활성 프로젝트 리더만 수행한다. (출처: `ai/evaluations/data/plan.md:11`, SHA-256 `f8e265adf8d8be5ee57309613f5bbe84035e3aa6af5fd98c7dc4bc4bc1be1dd8`)
- e-limit: 단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다. (출처: `ai/evaluations/data/requirements.md:7`, SHA-256 `f34e4411607078fcc70f508daa1308195c7f873c1fa3842a278228a4965bc5e2`)
- e-post: POST /api/projects/{projectId}/documents (출처: `ai/evaluations/data/api-spec.md:5`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-get: GET은 문서 목록을 조회하며 새 문서를 생성하지 않는다. (출처: `ai/evaluations/data/api-spec.md:31`, SHA-256 `1522fb43dde5f2db7b2e5628cf8cff3befe6ff3482cc3b7767b23d0bdd03141b`)
- e-owner: documents.owner_id는 accounts.id를 참조하며 삭제 정책은 RESTRICT다. (출처: `ai/evaluations/data/data-design.md:23`, SHA-256 `3177765e5bfa43cceca0dccc942c0cc25b5a904f53c8187fdb02fb54bb486c1a`)

### 입력된 관계

- 1002/1102 → 1001/1101: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1001/1101 → 1000/1100: DEPENDS_ON, EXPLICIT, CONFIRMED
- 1002/1102 → 1004/1104: DEPENDS_ON, EXPLICIT, NEEDS_RECHECK
- 1002/1102 → 1003/1103: DEPENDS_ON, AI_CANDIDATE, CONFIRMED
