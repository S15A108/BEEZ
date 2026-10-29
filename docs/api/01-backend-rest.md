# BEEZ Backend REST API

버전: 0.7 · 갱신일: 2026-10-28 · 개발 계약 초안

2026-10-28: 화면 수정안(v2) 요청을 반영했다(S15P31A108-218). 새 API(ACC-A11~A13, PRJ-A07·A08, MEM-A11·A12, DOC-A13~A23, CHG-A14·A15, BEE-A03·A04, MTG-A11~A13, MON-A06·A07)와 기존 API에서 바뀐 부분은 '수정안 요청'으로 표시했다. ACC-A08·A09와 MTG-A01·A04·A05·A07·A08·A09는 폐지 예정이다. 미정 정책은 [검토 항목](00-common.md#9-검토-항목) Q14~Q23이다.

웹 클라이언트가 backend를 호출하는 API를 정의한다. [공통 규약·권한·전체 목록](00-common.md), [MCP 도구](02-mcp-tools.md), [AI 내부 API](03-ai-internal.md)를 함께 참조한다. 경로의 `{id}`는 프로젝트 경로에서는 프로젝트 ID, 그 밖에는 해당 리소스 ID다.

각 절은 요청·응답·오류 예시와 처리 조건을 정의한다. 중첩 DTO의 필드·조건은 타입 링크의 공통 스키마를 따른다. 예제의 계정·ID·해시는 설명용 값이며 실제 비밀정보가 아니다.

## 탐색

- [ACC-A01 회원가입](#acc-a01)
- [ACC-A02 로그인](#acc-a02)
- [ACC-A03 로그아웃](#acc-a03)
- [ACC-A04 내 계정 조회](#acc-a04)
- [ACC-A05 프로필 수정](#acc-a05)
- [ACC-A06 비밀번호 변경](#acc-a06)
- [ACC-A07 임시 비밀번호 발급](#acc-a07)
- [ACC-A08 재설정 링크 확인 (폐지 예정)](#acc-a08)
- [ACC-A09 비밀번호 재설정 (폐지 예정)](#acc-a09)
- [ACC-A10 회원 탈퇴](#acc-a10)
- [ACC-A11 가입 인증 코드 보내기](#acc-a11)
- [ACC-A12 가입 인증 코드 확인](#acc-a12)
- [ACC-A13 탈퇴 가능 여부](#acc-a13)
- [PRJ-A01 프로젝트 생성](#prj-a01)
- [PRJ-A02 참여 프로젝트 목록](#prj-a02)
- [PRJ-A03 프로젝트 상세](#prj-a03)
- [PRJ-A04 프로젝트 정보 수정](#prj-a04)
- [PRJ-A05 프로젝트 삭제](#prj-a05)
- [PRJ-A06 초기 설정 현황](#prj-a06)
- [PRJ-A07 내 시작 가이드 상태](#prj-a07)
- [PRJ-A08 내 시작 가이드 상태 저장](#prj-a08)
- [MEM-A01 팀원 초대 생성](#mem-a01)
- [MEM-A02 초대 목록](#mem-a02)
- [MEM-A03 초대 폐기](#mem-a03)
- [MEM-A04 초대 미리보기](#mem-a04)
- [MEM-A05 초대 수락](#mem-a05)
- [MEM-A06 팀원 목록](#mem-a06)
- [MEM-A07 팀원 담당 영역·표시 수정](#mem-a07)
- [MEM-A08 팀장 위임](#mem-a08)
- [MEM-A09 팀원 제외](#mem-a09)
- [MEM-A10 프로젝트 나가기](#mem-a10)
- [MEM-A11 초대 다시 보내기](#mem-a11)
- [MEM-A12 내 대기 초대 목록](#mem-a12)
- [AGT-A01 에이전트 토큰 발급](#agt-a01)
- [AGT-A02 내 에이전트 토큰 목록](#agt-a02)
- [AGT-A03 에이전트 토큰 폐기](#agt-a03)
- [AGT-A04 에이전트 연결 안내](#agt-a04)
- [AGT-A05 에이전트 연결 현황 목록](#agt-a05)
- [AGT-A06 에이전트 연결 상세](#agt-a06)
- [DOC-A01 초기 문서 업로드](#doc-a01)
- [DOC-A02 문서 목록](#doc-a02)
- [DOC-A03 문서 원문 조회](#doc-a03)
- [DOC-A04 원본 파일 다운로드](#doc-a04)
- [DOC-A05 문서 버전 목록](#doc-a05)
- [DOC-A06 문서 버전 비교](#doc-a06)
- [DOC-A07 문서 분류 수정](#doc-a07)
- [PLN-A01 문서 분석 상태·결과 조회](#pln-a01)
- [PLN-A02 문서 분석 재시도](#pln-a02)
- [PLN-A03 기획 보완 질문 목록](#pln-a03)
- [PLN-A04 기획 보완 질문 답변](#pln-a04)
- [PLN-A05 답변을 반영한 설계 초안 생성](#pln-a05)
- [PLN-A06 설계 항목 목록](#pln-a06)
- [PLN-A07 설계 항목 초안 추가](#pln-a07)
- [PLN-A08 설계 항목 초안 수정](#pln-a08)
- [PLN-A09 설계 항목 초안 제외](#pln-a09)
- [PLN-A10 최초 기준 설계 승인·발행](#pln-a10)
- [DOC-A08 현재 기준 설계 조회](#doc-a08)
- [DOC-A09 기준 설계 이력 목록](#doc-a09)
- [DOC-A10 기준 설계 특정 버전 조회](#doc-a10)
- [DOC-A11 문서·설계 검색](#doc-a11)
- [TRC-A01 설계 항목 의존관계 조회](#trc-a01)
- [DOC-A12 변경용 원문 파일 업로드](#doc-a12)
- [DOC-A13 비비 검토 상태](#doc-a13)
- [DOC-A14 비비 검토 재시도](#doc-a14)
- [DOC-A15 비비 수정 제안 목록](#doc-a15)
- [DOC-A16 수정 제안 반영·넘기기](#doc-a16)
- [DOC-A17 수정 제안에 대해 비비와 대화](#doc-a17)
- [DOC-A18 비비에게 직접 수정 요청](#doc-a18)
- [DOC-A19 팀장 승인 요청](#doc-a19)
- [DOC-A20 승인 요청 취소](#doc-a20)
- [DOC-A21 문서 버전 승인](#doc-a21)
- [DOC-A22 문서 버전 반려](#doc-a22)
- [DOC-A23 반려 후 비비 재검토](#doc-a23)
- [CHG-A01 설계 변경 제안 생성](#chg-a01)
- [CHG-A02 설계 변경 제안 목록](#chg-a02)
- [CHG-A03 설계 변경 제안 상세·비교](#chg-a03)
- [CHG-A04 설계 변경 제안 갱신·재제출](#chg-a04)
- [CHG-A05 변경 영향 조회](#chg-a05)
- [CHG-A06 변경 영향 AI 재분석](#chg-a06)
- [CHG-A07 문서 수정 초안 생성](#chg-a07)
- [CHG-A08 변경 제안 검토 시작](#chg-a08)
- [CHG-A09 변경 제안 승인·발행](#chg-a09)
- [CHG-A10 변경 제안 반려](#chg-a10)
- [CHG-A11 승인 이력 조회](#chg-a11)
- [CHG-A12 변경 제안 의견·검토 요청 등록](#chg-a12)
- [CHG-A13 변경 제안 의견 목록](#chg-a13)
- [CHG-A14 승인 미리 보기](#chg-a14)
- [CHG-A15 변경 제안 거두기](#chg-a15)
- [TSK-A01 작업 계획 등록](#tsk-a01)
- [TSK-A02 작업 목록](#tsk-a02)
- [TSK-A03 작업 상세](#tsk-a03)
- [TSK-A04 작업 계획 새 리비전 저장](#tsk-a04)
- [TSK-A05 작업 계획 리비전 이력](#tsk-a05)
- [TSK-A06 작업 담당자 변경](#tsk-a06)
- [VAL-A01 작업 검증 접수](#val-a01)
- [VAL-A02 검증 결과 조회](#val-a02)
- [VAL-A03 작업 검증 이력](#val-a03)
- [VAL-A04 검증 실행 재시도](#val-a04)
- [TSK-A07 작업 상태 보고](#tsk-a07)
- [CTX-A01 개발 컨텍스트 패킷 조회](#ctx-a01)
- [CTX-A02 공유 작업 기록 검색](#ctx-a02)
- [CTX-A03 작업 기록 상세](#ctx-a03)
- [CTX-A04 공유 작업 기록 생성·리비전 추가](#ctx-a04)
- [CTX-A05 작업 설계 근거 추천](#ctx-a05)
- [CTX-A06 컨텍스트 조회 이력](#ctx-a06)
- [BEE-A01 비비에게 질문](#bee-a01)
- [BEE-A02 작업 역할 분담 추천](#bee-a02)
- [BEE-A03 비비 답변 피드백](#bee-a03)
- [BEE-A04 비비 현재 상태](#bee-a04)
- [MTG-A01 회의 생성 (폐지 예정)](#mtg-a01)
- [MTG-A02 회의 목록](#mtg-a02)
- [MTG-A03 회의 상세](#mtg-a03)
- [MTG-A04 회의 참여 요청 (폐지 예정)](#mtg-a04)
- [MTG-A05 회의 참여 응답 (폐지 예정)](#mtg-a05)
- [MTG-A06 회의 텍스트 기록](#mtg-a06)
- [MTG-A07 비비의 회의 맥락 분석 (폐지 예정)](#mtg-a07)
- [MTG-A08 회의록 생성 (폐지 예정)](#mtg-a08)
- [MTG-A09 회의 결정 기반 문서 수정안 (폐지 예정)](#mtg-a09)
- [MTG-A10 회의 기록 목록](#mtg-a10)
- [MTG-A11 비비 회의 정리](#mtg-a11)
- [MTG-A12 회의 정리 수정 요청](#mtg-a12)
- [MTG-A13 회의 끝내기](#mtg-a13)
- [MON-D01 문서 승인 현황](#mon-d01)
- [MON-C01 변경 제안 현황](#mon-c01)
- [MON-T01 작업 상태 현황](#mon-t01)
- [MON-V01 검증 상태 현황](#mon-v01)
- [MON-A02 프로젝트 AI 사용량·추정 비용](#mon-a02)
- [MON-A03 작업·설계·검증 관계](#mon-a03)
- [MON-A04 프로젝트 활동 타임라인](#mon-a04)
- [MON-A05 에이전트 현재 작업 현황](#mon-a05)
- [MON-A06 내 접속 상태 보고](#mon-a06)
- [MON-A07 팀원 접속 현황](#mon-a07)
- [NTF-A01 내 알림함](#ntf-a01)
- [NTF-A02 알림 읽음 처리](#ntf-a02)
- [SYS-A01 backend 기동 확인](#sys-a01)
- [SYS-A02 backend·AI 연결 확인](#sys-a02)

---

<a id="acc-a01"></a>

## ACC-A01. 회원가입

### POST `/api/auth/register`

회원가입 기능을 제공한다.

관련 요구사항: ACC-01  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | `string` | Y | 가입할 이메일 |
| `password` | `string` | Y | 비밀번호. 영문·숫자·특수문자를 모두 포함한 8~128자(기본안, Q04) |
| `name` | `string` | Y | 표시 이름 |
| `verified_token` | `string` | Y | ACC-A12에서 받은 가입 인증 토큰. 수정안 요청 |

```json
{
  "email": "member@example.com",
  "password": "example-password",
  "name": "김개발"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계정 ID |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값 또는 URL |
| `created_at` | `Time` | Y | 가입 시각 |

```json
{
  "id": "11",
  "email": "member@example.com",
  "name": "김개발",
  "avatar": "bee-yellow",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `email_already_exists` | 이미 등록된 이메일 |
| 400 | `password_policy_violation` | 비밀번호 규칙 위반. 어긴 조건은 `error.details.violations` |
| 400 | `email_not_verified` | 인증 토큰이 없거나 만료됐거나 다른 이메일의 토큰 |

```json
{
  "error": {
    "code": "email_already_exists",
    "message": "이미 등록된 이메일"
  }
}
```

#### 처리 규칙

- 계정 생성과 이메일 유일성 검사를 원자적으로 처리한다.
- 가입 응답으로 로그인 세션을 만들지 않는다. 로그인 API를 별도로 호출한다.
- (수정안 요청) 가입 전에 ACC-A11·A12로 이메일을 인증한다. `verified_token`의 이메일과 요청 email이 같아야 하고, 토큰은 가입 성공 때 소비한다.
- (수정안 요청) 비밀번호 규칙은 ACC-A06·임시 비밀번호와 같다. 화면도 같은 규칙으로 미리 검사하지만 최종 판정은 서버다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a02"></a>

## ACC-A02. 로그인

### POST `/api/auth/login`

로그인 기능을 제공한다.

관련 요구사항: ACC-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | `string` | Y | 가입 이메일 |
| `password` | `string` | Y | 비밀번호 |
| `remember_me` | `boolean` | N | 로그인 유지. 기본 false. 수정안 요청 |

```json
{
  "email": "member@example.com",
  "password": "example-password"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계정 ID |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값 또는 URL |
| `created_at` | `Time` | Y | 가입 시각 |
| `must_change_password` | `boolean` | Y | 임시 비밀번호로 로그인했으면 true. 수정안 화면에는 강제 이동이 없고 캔버스 메모가 권하는 안전장치다(Q04). 수정안 요청 |

```json
{
  "id": "11",
  "email": "member@example.com",
  "name": "김개발",
  "avatar": "bee-yellow",
  "created_at": "2026-10-23T03:00:00Z"
}
```

응답 헤더:

| 헤더 | 값·설명 |
| --- | --- |
| Set-Cookie | 세션 쿠키. 이름·속성은 Q04 |

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 401 | `invalid_credentials` | 이메일 또는 비밀번호 불일치 |
| 401 | `temporary_password_expired` | 유효 기간이 지난 임시 비밀번호 |

```json
{
  "error": {
    "code": "invalid_credentials",
    "message": "이메일 또는 비밀번호 불일치"
  }
}
```

#### 처리 규칙

- 성공하면 세션 쿠키를 발급한다. 응답 본문에는 에이전트 토큰이나 JWT를 넣지 않는다.
- 실패 메시지로 이메일 가입 여부를 구분하지 않는다.
- (수정안 요청) remember_me가 true면 세션 만료를 늘린다. 두 경우의 기간은 Q04다.
- (수정안 요청) 임시 비밀번호(ACC-A07)로 로그인하면 must_change_password=true다. 바꾸기 전에 다른 API를 막을지는 Q04다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a03"></a>

## ACC-A03. 로그아웃

### POST `/api/auth/logout`

로그아웃 기능을 제공한다.

관련 요구사항: ACC-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 웹 세션을 종료한다. 이미 만료된 세션의 재요청도 204다.
- 사용자에게 발급된 에이전트 토큰은 유지한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a04"></a>

## ACC-A04. 내 계정 조회

### GET `/api/me`

내 계정 조회 기능을 제공한다.

관련 요구사항: ACC-04  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계정 ID |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값 또는 URL |
| `created_at` | `Time` | Y | 가입 시각 |

```json
{
  "id": "11",
  "email": "member@example.com",
  "name": "김개발",
  "avatar": "bee-yellow",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 서버가 세션의 계정 ID를 사용한다. 다른 사용자 ID를 받지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a05"></a>

## ACC-A05. 프로필 수정

### PATCH `/api/me`

프로필 수정 기능을 제공한다.

관련 요구사항: ACC-04  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `name` | `string` | N | 변경할 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값/URL |

```json
{
  "name": "김개발",
  "avatar": "bee-yellow"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 계정 ID |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 표시 이름 |
| `avatar` | `string` | N | 아바타 식별값 또는 URL |
| `created_at` | `Time` | Y | 가입 시각 |

```json
{
  "id": "11",
  "email": "member@example.com",
  "name": "김개발",
  "avatar": "bee-yellow",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 허용 필드 중 하나 이상을 전달한다.
- 프로젝트 담당 영역·역할 라벨·색상은 팀원 관리에서 변경한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a06"></a>

## ACC-A06. 비밀번호 변경

### PUT `/api/me/password`

비밀번호 변경 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `current_password` | `string` | Y | 현재 비밀번호 |
| `new_password` | `string` | Y | 새 비밀번호. ACC-A01과 같은 규칙 |

```json
{
  "current_password": "current-password",
  "new_password": "new-password"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `current_password_mismatch` | 현재 비밀번호 불일치 |
| 400 | `password_policy_violation` | 비밀번호 규칙 위반 |

```json
{
  "error": {
    "code": "current_password_mismatch",
    "message": "현재 비밀번호 불일치"
  }
}
```

#### 처리 규칙

- 비밀번호 해시를 갱신한다. 다른 세션 종료 정책은 Q04다.
- 비밀번호·인증정보를 응답이나 로그에 남기지 않는다.
- (수정안 요청) 임시 비밀번호로 로그인한 사람은 current_password에 임시 비밀번호를 보낸다. 성공하면 must_change_password가 풀린다. 변경 뒤 완료 화면 없이 회원 설정으로 돌아간다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a07"></a>

## ACC-A07. 임시 비밀번호 발급

### POST `/api/auth/password/forgot`

이름·이메일을 확인해 임시 비밀번호를 메일로 보낸다(수정안 B안 확정, 지금은 재설정 링크).

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영(B안 임시 비밀번호)

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | `string` | Y | 가입 이메일 |
| `name` | `string` | Y | 가입 때 쓴 표시 이름. 앞뒤 공백을 빼고 비교. 수정안 요청 |

```json
{
  "email": "member@example.com",
  "name": "김동료"
}
```

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `message` | `string` | Y | 동일한 안내 메시지 |

```json
{
  "message": "재설정 가능한 계정이면 안내 메일을 전송합니다."
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 429 | `too_many_requests` | 같은 이메일·IP의 요청 횟수 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "필수 필드·타입·허용 값 오류"
  }
}
```

#### 처리 규칙

- 이메일 존재 여부에 관계없이 같은 202 응답을 반환한다.
- (수정안 요청) 재설정 링크 대신 임시 비밀번호를 메일로 보낸다. 이름과 이메일이 모두 맞을 때만 발급하고, 맞지 않아도 같은 202 응답을 준다.
- (수정안 요청) 임시 비밀번호는 ACC-A01 비밀번호 규칙을 만족하고 짧은 유효 기간을 가진다. 메일에는 '로그인한 뒤 회원 설정에서 비밀번호를 바꾸세요'를 넣는다. 길이·유효 기간·발송 한도·기존 비밀번호와 세션 처리는 Q04다.
- (수정안 요청) 메일에 비밀번호가 평문으로 남으므로 첫 로그인 때 변경을 강제한다(ACC-A02 must_change_password).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a08"></a>

## ACC-A08. 재설정 링크 확인 (폐지 예정)

### POST `/api/auth/password/reset/verify`

재설정 링크 확인 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 폐지 예정 · 수정안 요청

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token` | `string` | Y | 메일로 받은 재설정 토큰 |

```json
{
  "token": "example-reset-token"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `valid` | `boolean` | Y | 링크 유효 여부 |
| `expires_at` | `Time` | Y | 만료 시각 |

```json
{
  "valid": true,
  "expires_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `invalid_reset_token` | 알 수 없는 토큰 |
| 410 | `reset_token_expired` | 만료된 링크 |
| 409 | `reset_token_used` | 이미 소비된 링크 |

```json
{
  "error": {
    "code": "invalid_reset_token",
    "message": "알 수 없는 토큰"
  }
}
```

#### 처리 규칙

- 유효성 확인만 수행하며 토큰을 소비하지 않는다.
- (수정안 요청) 비밀번호 찾기를 임시 비밀번호(ACC-A07)로 정해 이 API와 메일 링크로 여는 재설정 화면(SCR-02)을 없앤다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a09"></a>

## ACC-A09. 비밀번호 재설정 (폐지 예정)

### POST `/api/auth/password/reset`

비밀번호 재설정 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 폐지 예정 · 수정안 요청

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token` | `string` | Y | 재설정 토큰 |
| `new_password` | `string` | Y | 새 비밀번호 |

```json
{
  "token": "example-reset-token",
  "new_password": "new-password"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `invalid_reset_token` | 무효 토큰 |
| 410 | `reset_token_expired` | 만료 |
| 409 | `reset_token_used` | 이미 사용 |

```json
{
  "error": {
    "code": "invalid_reset_token",
    "message": "무효 토큰"
  }
}
```

#### 처리 규칙

- 새 비밀번호 저장과 토큰 소비를 같은 트랜잭션으로 처리한다.
- 다른 세션 정리 정책은 Q04다.
- (수정안 요청) 비밀번호 찾기를 임시 비밀번호(ACC-A07)로 정해 이 API와 메일 링크로 여는 재설정 화면(SCR-02)을 없앤다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a10"></a>

## ACC-A10. 회원 탈퇴

### POST `/api/me/withdraw`

회원 탈퇴 기능을 제공한다.

관련 요구사항: ACC-06  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `password` | `string` | Y | 본인 확인 비밀번호 |

```json
{
  "password": "example-password"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `invalid_credentials` | 비밀번호 불일치 |
| 409 | `leader_of_team_projects` | 다른 활성 팀원이 있는 프로젝트의 팀장. `error.details.blocked_projects`에 [BlockedProject[]](00-common.md#schema-blockedproject) |

```json
{
  "error": {
    "code": "invalid_credentials",
    "message": "비밀번호 불일치"
  }
}
```

#### 처리 규칙

- (수정안 요청) 본인 말고 활성 팀원이 1명 이상 있는 프로젝트의 팀장이면 막는다. 혼자 쓰는 프로젝트와 팀원으로만 참여한 프로젝트는 막지 않는다. 회원 탈퇴 화면의 기본안은 팀원으로 참여한 프로젝트에서 자동으로 빠지고, 혼자 쓰는 프로젝트는 함께 삭제하며, 팀 프로젝트에 남긴 문서와 작업 기록은 보존이다. 확정은 Q05다.
- 계정 개인정보는 제거·익명화하고 토큰 전부 폐기·세션 종료를 처리한다.
- 작업·편집·사용량 이력은 탈퇴한 사용자로 보존한다.
- (수정안 요청) 판정은 탈퇴 요청 때 서버가 다시 한다. 화면을 연 뒤 팀원 수가 바뀌어도 ACC-A13 결과를 믿지 않는다. 탈퇴 뒤 완료 화면 없이 랜딩으로 간다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a11"></a>

## ACC-A11. 가입 인증 코드 보내기

### POST `/api/auth/email-verifications`

회원가입 전에 이메일로 인증 코드를 보낸다.

관련 요구사항: ACC-01 (화면 SCR-01-v2-register)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | `string` | Y | 가입할 이메일 |

```json
{
  "email": "member@example.com"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `verification_id` | `ID` | Y | 인증 요청 ID |
| `expires_at` | `Time` | Y | 코드 만료 시각. 화면은 이 값으로 남은 시간을 셈 |
| `resend_available_at` | `Time` | Y | 다시 받기가 가능한 시각 |

```json
{
  "verification_id": "41",
  "expires_at": "2026-10-28T03:05:00Z",
  "resend_available_at": "2026-10-28T03:01:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `email_already_exists` | 이미 등록된 이메일 |
| 429 | `too_many_requests` | 발송 횟수 초과 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "필수 필드·타입·허용 값 오류"
  }
}
```

#### 처리 규칙

- 코드 유효 시간은 5분이다(기본안). 다시 받으면 새 인증 요청을 만들고 이전 코드는 바로 무효다.
- 다시 받기 간격과 이메일·IP별 발송 한도는 Q04다.
- 코드는 해시로만 저장하고 응답·로그에 남기지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a12"></a>

## ACC-A12. 가입 인증 코드 확인

### POST `/api/auth/email-verifications/{verification_id}/confirm`

메일로 받은 코드를 확인하고 회원가입에 쓸 1회용 토큰을 준다.

관련 요구사항: ACC-01 (화면 SCR-01-v2-register, SCR-01-v2-register-code)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `verification_id` | `ID` | Y | 인증 요청 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `code` | `string` | Y | 메일로 받은 6자리 코드 |

```json
{
  "code": "483920"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `verified_token` | `string` | Y | ACC-A01에 넣는 1회용 토큰 |
| `email` | `string` | Y | 인증된 이메일 |
| `expires_at` | `Time` | Y | 토큰 만료 시각 |

```json
{
  "verified_token": "evt_example",
  "email": "member@example.com",
  "expires_at": "2026-10-28T03:35:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 400 | `verification_code_mismatch` | 코드 불일치. `error.details.remaining_attempts`에 남은 시도 |
| 404 | `not_found` | 없는 인증 요청 |
| 410 | `verification_expired` | 코드 만료 또는 다시 받기로 무효 |
| 429 | `too_many_attempts` | 틀린 입력 횟수 초과. 새 코드 필요 |

```json
{
  "error": {
    "code": "validation_failed",
    "message": "필수 필드·타입·허용 값 오류"
  }
}
```

#### 처리 규칙

- 틀린 입력 횟수를 제한한다(값은 Q04). 넘으면 이 인증 요청은 끝나고 코드를 새로 받아야 한다.
- 성공하면 코드를 소비한다. verified_token은 유효 기간이 짧고 가입 성공 때 소비된다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a13"></a>

## ACC-A13. 탈퇴 가능 여부

### GET `/api/me/withdrawal-check`

탈퇴 페이지가 막는 프로젝트와 이유를 보여 주도록 판정 결과를 미리 준다.

관련 요구사항: ACC-06 (화면 SCR-05-v2-withdraw)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `can_withdraw` | `boolean` | Y | 지금 탈퇴할 수 있는지 |
| `blocked_projects` | [BlockedProject[]](00-common.md#schema-blockedproject) | Y | 탈퇴를 막는 프로젝트. 없으면 [] |

```json
{
  "can_withdraw": false,
  "blocked_projects": [
    {
      "project_id": "101",
      "name": "BEEZ",
      "my_role": "LEADER",
      "member_count": 4
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- ACC-A10과 같은 판정을 쓴다. 본인 말고 활성 팀원이 있는 프로젝트의 팀장이면 막힌다.
- 화면은 막힌 프로젝트마다 팀장 위임(MEM-A08) 또는 프로젝트 삭제(PRJ-A05)로 잇는다. 할 일이 남으면 탈퇴 버튼을 끈다.
- 결과는 참고용이다. 최종 판정은 ACC-A10이 요청 시점에 다시 한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a01"></a>

## PRJ-A01. 프로젝트 생성

### POST `/api/projects`

프로젝트 생성 기능을 제공한다.

관련 요구사항: PRJ-01, BEE-01  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `name` | `string` | Y | 프로젝트명. 1~40자 |
| `description` | `string` | N | 설명. 0~300자 |
| `design` | `glass\|brick\|modern\|tower\|wood` | N | 건물 디자인. 생성 때 생략하면 glass. 수정안 요청 |

```json
{
  "name": "BEEZ",
  "description": "승인 설계 기반 협업"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `name` | `string` | Y | 프로젝트 이름 |
| `description` | `string` | N | 프로젝트 설명 |
| `my_role` | `LEADER\|MEMBER` | Y | 조회자의 프로젝트 역할 |
| `design` | `glass\|brick\|modern\|tower\|wood` | Y | 건물 디자인. 수정안 요청 |
| `phase` | `PREPARING\|STARTED\|BASELINED` | Y | 준비 중·시작됨·기준 있음. 수정안 요청 |
| `member_count` | `integer` | Y | 활성 멤버 수. 수정안 요청 |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 현재 기준 설계. 최초 발행 전 생략 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 시각 |

```json
{
  "id": "101",
  "name": "BEEZ",
  "description": "승인 설계를 공유하는 협업 서비스",
  "my_role": "LEADER",
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- project와 생성자의 LEADER 멤버십을 함께 생성한다.
- 비비의 프로젝트별 논리 인스턴스를 초기화한다. AI가 별도 사용자를 생성하는 것은 아니다.
- (수정안 요청) design을 서버에 저장해 팀원 모두에게 같은 건물을 보인다. 지금 화면은 브라우저에만 저장해 다른 기기·팀원에게는 기본 디자인으로 보인다.
- (수정안 요청) 이름 1~40자, 소개 0~300자, design은 정해진 값만 받는다. 위반은 400 validation_failed다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a02"></a>

## PRJ-A02. 참여 프로젝트 목록

### GET `/api/projects`

참여 프로젝트 목록 기능을 제공한다.

관련 요구사항: PRJ-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Project[]](00-common.md#schema-project) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "101",
      "name": "BEEZ",
      "description": "승인 설계를 공유하는 협업 서비스",
      "my_role": "LEADER",
      "current_baseline": {
        "id": "401",
        "version_no": 1,
        "created_at": "2026-10-23T03:00:00Z"
      },
      "created_at": "2026-10-23T03:00:00Z",
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 계정이 활성 멤버인 프로젝트만 페이지 처리 전에 필터링한다.
- 삭제 프로젝트는 노출하지 않는다.
- (수정안 요청) 각 항목에 design·phase·member_count를 넣어 도시 화면이 추가 호출 없이 건물과 '준비 중·시작됨'을 그린다. 검색·정렬은 화면에서 처리하고 `q`·`sort` 파라미터는 필요해지면 추가한다. 부지 번호 저장은 Q22다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a03"></a>

## PRJ-A03. 프로젝트 상세

### GET `/api/projects/{id}`

프로젝트 상세 기능을 제공한다.

관련 요구사항: PRJ-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `name` | `string` | Y | 프로젝트 이름 |
| `description` | `string` | N | 프로젝트 설명 |
| `my_role` | `LEADER\|MEMBER` | Y | 조회자의 프로젝트 역할 |
| `design` | `glass\|brick\|modern\|tower\|wood` | Y | 건물 디자인. 수정안 요청 |
| `phase` | `PREPARING\|STARTED\|BASELINED` | Y | 준비 중·시작됨·기준 있음. 수정안 요청 |
| `member_count` | `integer` | Y | 활성 멤버 수. 수정안 요청 |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 현재 기준 설계. 최초 발행 전 생략 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 시각 |

```json
{
  "id": "101",
  "name": "BEEZ",
  "description": "승인 설계를 공유하는 협업 서비스",
  "my_role": "LEADER",
  "current_baseline": {
    "id": "401",
    "version_no": 1,
    "created_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.
- (수정안 요청) phase는 필수 4종 등록 여부(STARTED)와 현재 기준 유무(BASELINED)로 계산한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a04"></a>

## PRJ-A04. 프로젝트 정보 수정

### PATCH `/api/projects/{id}`

프로젝트 정보 수정 기능을 제공한다.

관련 요구사항: PRJ-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `name` | `string` | N | 이름. 1~40자 |
| `description` | `string` | N | 설명. 0~300자 |
| `design` | `glass\|brick\|modern\|tower\|wood` | N | 건물 디자인. 생성 때 생략하면 glass. 수정안 요청 |
| `expected_updated_at` | `Time` | N | 조회한 updated_at. 보내면 낙관적 충돌 검사. 수정안 요청 |

```json
{
  "name": "BEEZ",
  "description": "수정한 설명"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `name` | `string` | Y | 프로젝트 이름 |
| `description` | `string` | N | 프로젝트 설명 |
| `my_role` | `LEADER\|MEMBER` | Y | 조회자의 프로젝트 역할 |
| `design` | `glass\|brick\|modern\|tower\|wood` | Y | 건물 디자인. 수정안 요청 |
| `phase` | `PREPARING\|STARTED\|BASELINED` | Y | 준비 중·시작됨·기준 있음. 수정안 요청 |
| `member_count` | `integer` | Y | 활성 멤버 수. 수정안 요청 |
| `current_baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 현재 기준 설계. 최초 발행 전 생략 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 시각 |

```json
{
  "id": "101",
  "name": "BEEZ",
  "description": "승인 설계를 공유하는 협업 서비스",
  "my_role": "LEADER",
  "current_baseline": {
    "id": "401",
    "version_no": 1,
    "created_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | expected_updated_at이 현재와 다름 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 하나 이상의 변경 필드가 필요하다.
- 멤버십·기준 설계·작업 상태는 이 요청으로 바꾸지 않는다.
- (수정안 요청) 팀장이 바꾼 이름·디자인은 팀원이 다시 불러올 때 반영하고 활동 기록에 남긴다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a05"></a>

## PRJ-A05. 프로젝트 삭제

### DELETE `/api/projects/{id}`

프로젝트 삭제 기능을 제공한다.

관련 요구사항: PRJ-05  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `confirm_name` | `string` | Y | 현재 프로젝트명을 입력 |

```json
{
  "confirm_name": "BEEZ"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `confirmation_mismatch` | 이름 확인 실패 |
| 409 | `members_exist` | 본인 말고 활성 팀원이 있음. 팀장 위임(MEM-A08) 뒤 나가기(MEM-A10)로 안내 |

```json
{
  "error": {
    "code": "confirmation_mismatch",
    "message": "이름 확인 실패"
  }
}
```

#### 처리 규칙

- 프로젝트를 soft delete하고 멤버·에이전트의 일반 접근을 차단한다.
- 원본·버전·감사 이력을 물리 삭제하지 않는다.
- (수정안 요청) 본인 말고 활성 팀원이 있으면 이름이 맞아도 409 members_exist로 거절한다. 혼자 쓰는 프로젝트만 삭제한다. 판정은 요청 시점에 다시 한다. MEM-A10의 leader_transfer_required와 같은 흐름이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a06"></a>

## PRJ-A06. 초기 설정 현황

### GET `/api/projects/{id}/onboarding`

초기 설정 현황 기능을 제공한다.

관련 요구사항: PRJ-04  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `required_documents` | [RequiredDocument[]](00-common.md#schema-requireddocument) | Y | 필수 문서 4종 |
| `baseline_published` | `boolean` | Y | 최초 기준 발행됨 |
| `member_count` | `integer` | Y | 활성 멤버 수 |
| `my_agent_connected` | `boolean` | Y | 본인 연결 관측 |
| `documents_ready_at` | `Time` | N | 필수 4종이 처음 모두 올라간 시각. '프로젝트가 시작됐어요'를 한 번만 띄움. 수정안 요청 |

```json
{
  "required_documents": [
    {
      "kind": "REQUIREMENTS",
      "uploaded": true,
      "approved": false
    }
  ],
  "baseline_published": false,
  "member_count": 1,
  "my_agent_connected": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 필수 kind 4종의 업로드·승인을 각각 표시한다.
- 문서 등록·팀 구성·연결 안내에 사용하며 자동 승인하지 않는다.
- (수정안 요청) required_documents[]에 document_id·latest_version_id·review_stage를 넣어 팀장 시작 가이드가 문서별 비비 검토·승인 단계를 그린다.
- (수정안 요청) 4종이 모두 승인되면 기준 v1이 생긴다(DOC-A21, Q15). 그 전에는 변경 제안·작업 계획을 받지 않는다(CHG-A01·TSK-A01 baseline_required).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a07"></a>

## PRJ-A07. 내 시작 가이드 상태

### GET `/api/projects/{id}/me/guide-state`

공용 가이드·역할별 시작 가이드를 봤는지 계정·프로젝트별로 돌려준다.

관련 요구사항: PRJ-04 (화면 SCR-06-v2-tour-welcome, SCR-06-v2-workspace-preparing, SCR-06-v2-workspace-member)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `tour_seen_at` | `Time` | N | 공용 가이드를 보거나 건너뛴 시각 |
| `leader_guide_closed_at` | `Time` | N | 팀장 시작 가이드를 숨기거나 끝낸 시각 |
| `member_guide_closed_at` | `Time` | N | 팀원 시작 가이드를 숨기거나 끝낸 시각 |
| `baseline_read_at` | `Time` | N | '기준 설계 읽기'를 체크한 시각 |
| `updated_at` | `Time` | N | 마지막 변경. 저장한 적이 없으면 생략 |

```json
{
  "tour_seen_at": "2026-10-28T03:00:00Z",
  "updated_at": "2026-10-28T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 기기를 바꿔도 가이드가 다시 뜨지 않도록 서버에 둔다. 가이드 단계 자체는 PRJ-A06으로 계산한다.
- 저장한 적이 없으면 모든 시각을 생략한 객체를 돌려준다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a08"></a>

## PRJ-A08. 내 시작 가이드 상태 저장

### PATCH `/api/projects/{id}/me/guide-state`

가이드를 보거나 숨기거나 끝낸 것, '기준 설계 읽기' 체크를 저장한다.

관련 요구사항: PRJ-04 (화면 SCR-06-v2-tour-welcome, SCR-06-v2-workspace-member)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `tour_seen` | `boolean` | N | true면 지금 시각을 기록, false면 지움 |
| `leader_guide_closed` | `boolean` | N | 팀장 가이드. 현재 팀장만 |
| `member_guide_closed` | `boolean` | N | 팀원 가이드 |
| `baseline_read` | `boolean` | N | '기준 설계 읽기' 체크 |

```json
{
  "tour_seen": true
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `tour_seen_at` | `Time` | N | 공용 가이드를 보거나 건너뛴 시각 |
| `leader_guide_closed_at` | `Time` | N | 팀장 시작 가이드를 숨기거나 끝낸 시각 |
| `member_guide_closed_at` | `Time` | N | 팀원 시작 가이드를 숨기거나 끝낸 시각 |
| `baseline_read_at` | `Time` | N | '기준 설계 읽기'를 체크한 시각 |
| `updated_at` | `Time` | N | 마지막 변경. 저장한 적이 없으면 생략 |

```json
{
  "tour_seen_at": "2026-10-28T03:00:00Z",
  "updated_at": "2026-10-28T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 공통 PATCH 규약대로 하나 이상의 필드가 필요하다.
- leader_guide_closed는 현재 팀장만 바꾼다. 다른 멤버는 403이다.
- 가이드 종료 조건과 팀장을 넘겨받은 사람에게 다시 띄울지는 Q22다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a01"></a>

## MEM-A01. 팀원 초대 생성

### POST `/api/projects/{id}/invitations`

팀원 초대 생성 기능을 제공한다.

관련 요구사항: MEM-01  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | `string` | N | 지정하면 해당 이메일로 초대 |
| `emails` | `string[]` | N | 여러 이메일을 한 번에 초대하고 서버가 메일을 보냄. email과 둘 중 하나. 최대 개수는 Q05. 수정안 요청 |
| `message` | `string` | N | '함께 보낼 말'. 초대 메일에 넣음. 수정안 요청 |

```json
{
  "email": "member@example.com"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `invitation` | [Invitation](00-common.md#schema-invitation) | Y | 초대 메타데이터 |
| `invite_url` | `string` | Y | 초대 링크 |
| `results` | `object[]` | N | emails로 요청하면 이메일별 {email, status: CREATED\|ALREADY_MEMBER\|ALREADY_INVITED, invitation_id?}. 이때 invitation·invite_url은 생략. 수정안 요청 |

```json
{
  "invitation": {
    "id": "31",
    "project_id": "101",
    "invited_email": "member@example.com",
    "expires_at": "2026-10-23T03:00:00Z"
  },
  "invite_url": "https://beez.example/invite?token=example-token"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `already_member` | 이미 활성 멤버 |
| 429 | `too_many_requests` | 메일 발송 한도 초과 |

```json
{
  "error": {
    "code": "already_member",
    "message": "이미 활성 멤버"
  }
}
```

#### 처리 규칙

- 초대 링크 방식의 계약이다. 메일은 보내지 않고 팀장이 `invite_url`을 전달한다. 토큰은 이 응답에서 한 번만 주고 서버에는 해시만 둔다.
- 만료는 생성 후 7일, 수락 1회로 끝난다(Q05 제안값, -174). 같은 이메일의 대기 초대가 있어도 새로 만든다.
- `email`은 앞뒤 공백을 빼고 소문자로 저장한다. 그 이메일의 계정이 이미 활성 멤버면 409 `already_member`.
- 링크의 비밀 토큰을 목록·로그에 그대로 노출하지 않는다.
- (수정안 요청) emails로 요청하면 서버가 초대 메일을 보낸다. 메일에는 초대한 사람·프로젝트·만료와 '초대 확인하기' 버튼만 있고, 버튼은 웹 초대 화면(MEM-A04)으로 간다. 링크 토큰은 메일에만 담는다. 문구·발신 주소·발송 한도는 Q05다.
- (수정안 요청) 화면은 링크 복사 대신 메일 보내기만 쓴다. email·invite_url 방식은 기존 호환으로 남긴다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a02"></a>

## MEM-A02. 초대 목록

### GET `/api/projects/{id}/invitations`

초대 목록 기능을 제공한다.

관련 요구사항: MEM-01  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |
| `status` | `PENDING\|ACCEPTED\|REVOKED\|EXPIRED` | N | 상태 필터. 설정 › 팀의 '초대 대기' 줄은 PENDING. 수정안 요청 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Invitation[]](00-common.md#schema-invitation) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "31",
      "project_id": "101",
      "invited_email": "member@example.com",
      "expires_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.
- 대기·수락·폐기·만료 초대를 모두 id 내림차순으로 돌려준다. 상태는 `accepted_at`·`revoked_at`·`expires_at`으로 구분한다.
- (수정안 요청) 대기 초대 줄의 '다시 보내기'는 MEM-A11, '초대 취소'는 MEM-A03이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a03"></a>

## MEM-A03. 초대 폐기

### DELETE `/api/projects/{id}/invitations/{invitation_id}`

초대 폐기 기능을 제공한다.

관련 요구사항: MEM-01  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `invitation_id` | `ID` | Y | 초대 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 폐기 후 링크 수락을 거부한다. 본 프로젝트의 이미 폐기된 초대는 재요청 204다.
- 이미 수락된 초대는 바꾸지 않고 204다. 들어온 팀원을 내보내는 것은 [MEM-A09](#mem-a09)다.
- 다른 프로젝트의 초대 ID는 404다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a04"></a>

## MEM-A04. 초대 미리보기

### POST `/api/invitations/preview`

초대 미리보기 기능을 제공한다.

관련 요구사항: MEM-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token` | `string` | Y | 초대 링크의 토큰 |

```json
{
  "token": "example-invitation-token"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_name` | `string` | Y | 프로젝트명 |
| `expires_at` | `Time` | Y | 만료 시각 |
| `login_required` | `boolean` | Y | 수락 시 로그인 필요 |
| `inviter` | `object` | Y | 초대한 팀장 {name, email}. 수정안 요청 |
| `project_description` | `string` | N | 프로젝트 소개. 화면은 두 줄 넘으면 말줄임. 수정안 요청 |
| `invited_email` | `string` | N | 이메일을 지정한 초대면 그 주소. 수정안 요청 |
| `account_matches` | `boolean` | N | 로그인 상태면 로그인 계정이 초대 대상과 맞는지. 다르면 화면이 수락 전에 다시 로그인하게 함. 수정안 요청 |

```json
{
  "project_name": "BEEZ",
  "expires_at": "2026-10-23T03:00:00Z",
  "login_required": true
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 404 | `not_found` | 무효 초대 |
| 410 | `invitation_expired` | 만료. 수정안 요청: 404 `not_found`로 합침 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 프로젝트명·만료 안내에 필요한 최소 정보만 반환한다.
- 초대를 소비하거나 멤버십을 만들지 않는다.
- 없는 토큰·폐기·이미 수락·삭제된 프로젝트의 초대는 모두 404, 만료는 410이다. 404 메시지는 공통 문구로 같다(사유는 서버 로그에만). `login_required`는 요청에 로그인 세션이 없으면 `true`다.
- (수정안 요청) 만료도 404로 합쳐 유효하지 않은 초대(기간 지남·취소·이미 수락·프로젝트 삭제·잘못된 링크)를 not-found 결과 하나로 준다. 화면은 원인을 알리지 않고 '페이지를 찾을 수 없어요'를 보인다. 통신 실패만 따로 다시 불러오기를 둔다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a05"></a>

## MEM-A05. 초대 수락

### POST `/api/invitations/accept`

초대 수락 기능을 제공한다.

관련 요구사항: MEM-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token` | `string` | N | 초대 토큰. invitation_id와 둘 중 하나 |
| `invitation_id` | `ID` | N | MEM-A12에서 고른 대기 초대. 로그인 계정 이메일이 초대 이메일과 같을 때만. 수정안 요청 |

```json
{
  "token": "example-invitation-token"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project` | [Project](00-common.md#schema-project) | Y | 참여한 프로젝트 |
| `member` | [Member](00-common.md#schema-member) | Y | 생성한 MEMBER 멤버십 |

```json
{
  "project": {
    "id": "101",
    "name": "BEEZ",
    "description": "승인 설계를 공유하는 협업 서비스",
    "my_role": "MEMBER",
    "current_baseline": {
      "id": "401",
      "version_no": 1,
      "created_at": "2026-10-23T03:00:00Z"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "updated_at": "2026-10-23T03:00:00Z"
  },
  "member": {
    "id": "21",
    "account_id": "11",
    "name": "김개발",
    "role": "MEMBER",
    "domain": "backend",
    "role_label": "백엔드",
    "color": "#FFD54F",
    "agent_connected": true
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 404 | `not_found` | 무효 초대(없는 토큰·폐기·삭제된 프로젝트·다른 이메일 지정) |
| 409 | `invitation_used` | 이미 소비된 초대. 수정안 요청: 404로 합침 |
| 409 | `already_member` | 이미 참여 |
| 410 | `invitation_expired` | 만료. 수정안 요청: 404로 합침 |

```json
{
  "error": {
    "code": "invitation_used",
    "message": "이미 소비된 초대"
  }
}
```

#### 처리 규칙

- 로그인 계정과 초대 대상 조건을 검사하고 초대 소비·멤버십 생성을 원자적으로 처리한다. 같은 링크를 동시에 수락해도 한 명만 성공한다.
- 이메일 지정 초대는 같은 이메일(대소문자 무시) 계정만 수락할 수 있다. 다르면 무효 초대와 같은 404다(Q05 제안, -174).
- 이미 활성 멤버면 409 `already_member`이고 초대는 소비되지 않는다.
- 제외·나가기로 끝난 멤버십이 있으면 새로 만들지 않고 그 멤버십을 MEMBER로 되살린다. 담당 영역·라벨·색상은 비운다.
- (수정안 요청) 유효하지 않은 초대는 MEM-A04와 같이 404 하나로 준다. already_member는 그대로 두고 화면은 그 프로젝트로 보낸다.
- (수정안 요청) 로그아웃 상태에서 초대를 열고 회원가입을 거쳐도 같은 토큰으로 이 API를 부른다. 가입한 이메일로 온 대기 초대는 MEM-A12로 다시 보여 준다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a06"></a>

## MEM-A06. 팀원 목록

### GET `/api/projects/{id}/members`

팀원 목록 기능을 제공한다.

관련 요구사항: MEM-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Member[]](00-common.md#schema-member) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "21",
      "account_id": "11",
      "name": "김개발",
      "role": "MEMBER",
      "domain": "backend",
      "role_label": "백엔드",
      "color": "#FFD54F",
      "agent_connected": true
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a07"></a>

## MEM-A07. 팀원 담당 영역·표시 수정

### PATCH `/api/projects/{id}/members/{member_id}`

팀원 담당 영역·표시 수정 기능을 제공한다.

관련 요구사항: MEM-04·05  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `member_id` | `ID` | Y | 멤버 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `domain` | `string` | N | 담당 영역 |
| `role_label` | `string` | N | 역할 라벨 |
| `color` | `string` | N | 색상 |

```json
{
  "domain": "backend",
  "role_label": "백엔드",
  "color": "#FFD54F"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 멤버 ID |
| `account_id` | `ID` | Y | 계정 ID |
| `name` | `string` | Y | 표시 이름 |
| `role` | `LEADER\|MEMBER` | Y | 프로젝트 역할 |
| `domain` | `string` | N | 담당 영역 |
| `role_label` | `string` | N | 프로젝트 내 역할 라벨 |
| `color` | `string` | N | 표시 색상 |
| `agent_connected` | `boolean` | Y | 에이전트 연결 관측 여부 |

```json
{
  "id": "21",
  "account_id": "11",
  "name": "김개발",
  "role": "MEMBER",
  "domain": "backend",
  "role_label": "백엔드",
  "color": "#FFD54F",
  "agent_connected": true
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 하나 이상 필드를 전달한다. LEADER/MEMBER 권한은 이 요청으로 바꿀 수 없다.
- 담당 영역·라벨·색상은 계정 프로필이 아닌 프로젝트별 정보다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a08"></a>

## MEM-A08. 팀장 위임

### POST `/api/projects/{id}/leadership-transfer`

팀장 위임 기능을 제공한다.

관련 요구사항: MEM-06  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `target_member_id` | `ID` | Y | 위임받을 활성 멤버 |

```json
{
  "target_member_id": "22"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `members` | [Member[]](00-common.md#schema-member) | Y | 권한이 바뀐 두 멤버 |

```json
{
  "members": [
    {
      "id": "21",
      "account_id": "11",
      "name": "김개발",
      "role": "MEMBER",
      "domain": "backend",
      "role_label": "백엔드",
      "color": "#FFD54F",
      "agent_connected": true
    },
    {
      "id": "22",
      "account_id": "12",
      "name": "김개발",
      "role": "LEADER",
      "domain": "backend",
      "role_label": "백엔드",
      "color": "#FFD54F",
      "agent_connected": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `invalid_transfer_target` | 본인·비활성 대상 |

```json
{
  "error": {
    "code": "invalid_transfer_target",
    "message": "본인·비활성 대상"
  }
}
```

#### 처리 규칙

- 현재 LEADER를 MEMBER로, 대상을 LEADER로 같은 트랜잭션에서 변경한다.
- 활성 팀장 1명 조건을 유지하며 요청 처리 시점의 팀장 권한을 재확인한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a09"></a>

## MEM-A09. 팀원 제외

### POST `/api/projects/{id}/members/{member_id}/remove`

팀원 제외 기능을 제공한다.

관련 요구사항: MEM-06  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `member_id` | `ID` | Y | 멤버 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `reassign_to_member_id` | `ID` | N | 열린 작업 인계 대상 |

```json
{
  "reassign_to_member_id": "22"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `leader_transfer_required` | 팀장은 위임 전 제외 불가 |
| 409 | `active_tasks_exist` | 정리되지 않은 열린 작업 |

```json
{
  "error": {
    "code": "leader_transfer_required",
    "message": "팀장은 위임 전 제외 불가"
  }
}
```

#### 처리 규칙

- 같은 프로젝트의 활성 대상·인계 대상을 확인한다.
- 멤버십 종료 후 기존 토큰의 접근을 즉시 거부한다. 작업 소유권 처리 세부는 Q05다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a10"></a>

## MEM-A10. 프로젝트 나가기

### POST `/api/projects/{id}/leave`

프로젝트 나가기 기능을 제공한다.

관련 요구사항: ACC-06, MEM-06  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `reassign_to_member_id` | `ID` | N | 작업 인계 대상 |

```json
{
  "reassign_to_member_id": "22"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `leader_transfer_required` | 팀장은 위임 선행 |
| 409 | `active_tasks_exist` | 미정리 작업 |

```json
{
  "error": {
    "code": "leader_transfer_required",
    "message": "팀장은 위임 선행"
  }
}
```

#### 처리 규칙

- 현재 호출자의 멤버십만 종료한다.
- 종료한 멤버십으로 발급한 토큰은 즉시 사용할 수 없다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a11"></a>

## MEM-A11. 초대 다시 보내기

### POST `/api/projects/{id}/invitations/{invitation_id}/resend`

대기 중인 초대의 메일을 새 링크로 다시 보낸다.

관련 요구사항: MEM-01 (화면 SCR-06-v2-settings-invited)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `invitation_id` | `ID` | Y | 초대 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `invitation` | [Invitation](00-common.md#schema-invitation) | Y | 갱신한 초대 |

```json
{
  "invitation": {
    "id": "31",
    "project_id": "101",
    "invited_email": "member@example.com",
    "expires_at": "2026-11-04T03:00:00Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `invitation_not_pending` | 이미 수락·폐기된 초대 |
| 429 | `too_many_requests` | 발송 한도 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 새 토큰을 만들어 메일을 다시 보내고 만료를 지금부터 7일로 다시 잡는다. 이전 링크는 바로 무효다.
- 링크 토큰은 응답에 넣지 않는다. 발송 한도는 Q05다.
- 이메일을 지정한 초대만 다시 보낼 수 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a12"></a>

## MEM-A12. 내 대기 초대 목록

### GET `/api/me/invitations`

로그인한 계정의 이메일로 온 대기 초대를 보여 준다.

관련 요구사항: MEM-02 (화면 SCR-03-v2-invite-logged-out)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | `object[]` | Y | 대기 초대 {invitation_id, project_name, inviter{name, email}, expires_at} |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "invitation_id": "31",
      "project_name": "BEEZ",
      "inviter": {
        "name": "이팀장",
        "email": "leader@example.com"
      },
      "expires_at": "2026-11-04T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 로그인 계정 이메일(대소문자 무시)로 지정된, 만료 전·미수락·미폐기 초대만 돌려준다.
- 초대받고 회원가입한 사람에게 가입·로그인 직후 이어서 보여 준다. 수락은 MEM-A05에 invitation_id를 보낸다.
- 링크 토큰은 돌려주지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a01"></a>

## AGT-A01. 에이전트 토큰 발급

### POST `/api/projects/{id}/agent-tokens`

에이전트 토큰 발급 기능을 제공한다.

관련 요구사항: ACC-05, AGT-01·04  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER, 본인 소유 객체`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `label` | `string` | N | 토큰 구분 이름 |

```json
{
  "label": "개발 PC"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token_info` | [AgentToken](00-common.md#schema-agenttoken) | Y | 발급 메타데이터 |
| `token` | `string` | Y | 발급 시 한 번만 표시하는 평문 |
| `mcp_url` | `string` | Y | 연결 URL |
| `claude_md_snippet` | `string` | Y | 작업 절차 지침 |

```json
{
  "token_info": {
    "id": "41",
    "project_id": "101",
    "label": "개발 PC",
    "created_at": "2026-10-23T03:00:00Z",
    "last_used_at": "2026-10-23T03:00:00Z"
  },
  "token": "example-agent-token",
  "mcp_url": "https://beez.example/mcp",
  "claude_md_snippet": "승인 설계 조회 → 계획 등록 → 검증 → 시작 → 완료 보고"
}
```

응답 헤더:

| 헤더 | 값·설명 |
| --- | --- |
| Cache-Control | no-store |

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `agent_limit_reached` | 활성 연결 수 정책 위반(Q06) |

```json
{
  "error": {
    "code": "agent_limit_reached",
    "message": "활성 연결 수 정책 위반(Q06)"
  }
}
```

#### 처리 규칙

- 본인 토큰만 발급하며 해시만 DB에 저장한다.
- 평문은 이 응답에서만 제공한다. 목록·재조회·로그에 노출하지 않는다.
- 응답 유실 시 토큰 목록을 확인해 해당 발급분을 폐기하고 다시 발급한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a02"></a>

## AGT-A02. 내 에이전트 토큰 목록

### GET `/api/me/agent-tokens`

내 에이전트 토큰 목록 기능을 제공한다.

관련 요구사항: ACC-05  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | N | 프로젝트 필터 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [AgentToken[]](00-common.md#schema-agenttoken) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "41",
      "project_id": "101",
      "label": "개발 PC",
      "created_at": "2026-10-23T03:00:00Z",
      "last_used_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 계정의 토큰 메타데이터만 반환한다. 평문·해시는 반환하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a03"></a>

## AGT-A03. 에이전트 토큰 폐기

### DELETE `/api/agent-tokens/{id}`

에이전트 토큰 폐기 기능을 제공한다.

관련 요구사항: ACC-05, AGT-06  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`USER, 본인 소유 객체`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 본인 토큰 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 계정 소유 토큰만 폐기한다. 타인 토큰은 404다.
- 폐기 커밋 이후 MCP·훅 호출에서 즉시 거부한다. 이미 폐기된 본인 토큰도 204다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a04"></a>

## AGT-A04. 에이전트 연결 안내

### GET `/api/projects/{id}/agent-connection-guide`

에이전트 연결 안내 기능을 제공한다.

관련 요구사항: AGT-04  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `mcp_url` | `string` | Y | MCP URL |
| `claude_md_snippet` | `string` | Y | 프로젝트별 작업 지침 |
| `steps` | `string[]` | Y | 연결 절차 |

```json
{
  "mcp_url": "https://beez.example/mcp",
  "claude_md_snippet": "계획 검증 후 START를 보고한다.",
  "steps": [
    "토큰 발급",
    "MCP 등록",
    "실제 연결 시험"
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 평문 토큰을 재발급·복원하지 않는다.
- 연결 시험은 실제 토큰으로 /mcp 초기화·도구 조회를 수행한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a05"></a>

## AGT-A05. 에이전트 연결 현황 목록

### GET `/api/projects/{id}/agents`

에이전트 연결 현황 목록 기능을 제공한다.

관련 요구사항: AGT-05, MON-07  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [AgentConnection[]](00-common.md#schema-agentconnection) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "51",
      "member_id": "21",
      "connected": true,
      "last_report_at": "2026-10-23T03:00:00Z",
      "status_check_required": false
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 연결·마지막 통신 관측만 제공한다. 통신 성공을 작업 실행 중으로 해석하지 않는다.
- 작업·검증·대기 사유는 MON-A05로 조회한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="agt-a06"></a>

## AGT-A06. 에이전트 연결 상세

### GET `/api/projects/{id}/agents/{agent_session_id}`

에이전트 연결 상세 기능을 제공한다.

관련 요구사항: MON-07  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `agent_session_id` | `ID` | Y | 에이전트 세션 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 에이전트 세션 ID |
| `member_id` | `ID` | Y | 소유 멤버 |
| `connected` | `boolean` | Y | 최근 연결 관측 |
| `last_report_at` | `Time` | N | 마지막 통신 시각 |
| `status_check_required` | `boolean` | Y | 보고 지연으로 확인 필요 |

```json
{
  "id": "51",
  "member_id": "21",
  "connected": true,
  "last_report_at": "2026-10-23T03:00:00Z",
  "status_check_required": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 관측 임계값을 넘기면 status_check_required를 표시한다. 자동 완료·점유 해제는 하지 않는다.
- (수정안 요청) 연결 확인 화면은 이 API를 다시 읽어 결과를 보인다. 실패면 last_error_code로 이유와 할 일을 한 줄로, 성공이면 마지막 보고 시각을 보인다.
- (수정안 요청) 연결 확인 실패는 활동(MON-A04)에도 남긴다. 대시보드는 '연결 확인 실패 · 토큰 폐기됨'을 에이전트의 최근 활동으로 보인다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a01"></a>

## DOC-A01. 초기 문서 업로드

#### 확정 업로드 정책 — 2026-10-27

| 항목 | 확정 규칙 |
| --- | --- |
| 입력 | .md/.txt, 엄격 UTF-8. JSON/YAML/DDL 전용 파싱은 후속 |
| 한도 | 파일당 1 MiB(1,048,576 bytes), 정규화 후 100,000 Unicode code points. 요청당 파일 1개, multipart 전체 2 MiB |
| 검증 | 빈/공백뿐인 원문, 잘못된 UTF-8, 개행/탭 외 바이너리 제어 문자 거부. 사용자 Content-Type만 신뢰하지 않음 |
| 정규화 | 첫 BOM 제거, CRLF/CR→LF. trim/NFKC 금지. 원본/정규화 텍스트 해시는 별개 |
| 중복 | 프로젝트별 PLAN/REQUIREMENTS/API_SPEC/ERD 논리 문서 각 1개. 동일 종류 새 등록은 409 document_kind_exists |
| 수정 | 원본 덮어쓰기 금지. DOC-A12에서 현재 승인본을 기준으로 새 DRAFT 생성 |
| 저장 | 로컬 개발·배포 모두 SeaweedFS 비공개 S3 bucket. 로컬은 Docker volume에 영속 저장한다. UUID storage_key 유지, DB에는 원문 텍스트·버전 저장. 경로/자격증명 외부 노출 금지 |
| 다운로드 | 원본 bytes, attachment. 원본명 대신 document-{document_id}-v{version_no}.{md\|txt} 생성 이름 |
| 접수 | 문서/버전·영속 분석 작업·멱등 응답을 같은 DB 트랜잭션으로 저장 후 201/PENDING. AI 완료/승인을 뜻하지 않음 |
| 재시도 | Idempotency-Key 필수. 같은 사용자/프로젝트/동작/키·입력은 24시간 같은 응답, 입력이 다르면 409 idempotency_conflict |

문서 API는 공통 로그인(PR #22)의 세션과 CallerContext로 사용자를 식별하고 현재 멤버십을 검사한다. X-Beez-Account-Id 헤더와 문서 전용 세션 속성으로 인증하지 않는다. 문서 전용 CSRF 토큰은 사용하지 않으며 GET /api/session/csrf는 제공하지 않는다. 업로드/분류/실패 분석 재시도는 사람 LEADER, 조회/다운로드/변경용 업로드는 현재 MEMBER다.

문서 변경 요청(DOC-A01·A07·A12, PLN-A02)은 Origin을 먼저 검사하고, 헤더 자체가 없을 때만 Referer의 출처를 검사한다. scheme·host·유효 port는 beez.cors.allowed-origins(CORS_ALLOWED_ORIGINS)의 명시적 HTTP(S) origin과 정확히 일치해야 한다. Origin: null·빈 값·중복·잘못된 형식·미허용 출처는 Referer로 우회하지 못한다. 출처가 둘 다 없거나 검증에 실패하면 403 request_origin_rejected이며 저장·변경을 수행하지 않는다. GET 조회에는 이 검사를 요구하지 않는다. 브라우저가 헤더를 전송하므로 FE가 Origin을 직접 설정하거나 토큰을 선조회하지 않는다.

공통 CORS 처리에서 먼저 차단된 요청도 403이지만 그 응답 본문은 공통 CORS 구현을 따른다. request_origin_rejected는 문서 출처 검사가 반환하는 오류 코드다.

로컬 Swagger에서 변경 요청을 시험할 때는 Swagger 화면의 실제 origin(예: http://localhost:8080)도 CORS_ALLOWED_ORIGINS에 명시해야 한다. curl·통합 테스트 등 브라우저 밖의 클라이언트는 유효 세션 쿠키와 허용된 Origin 또는 Referer를 함께 보낸다. 이는 사용자 인증을 대신하지 않는다. CORS 허용 목록의 와일드카드·경로·접미사 일치는 허용하지 않는다. 공통 로그인·SecurityConfig·다른 도메인의 정책은 이 변경에서 수정하지 않는다.

한도는 413 file_too_large, 미지원 확장자는 415 unsupported_media_type, UTF-8/빈 입력 등은 400 validation_failed다. DOC-A12에도 같은 정책을 적용한다. 프록시 한도는 인프라 담당에게 별도 전달하며 배포 설정은 변경하지 않는다.

### POST `/api/projects/{id}/documents`

초기 문서 업로드 기능을 제공한다.

관련 요구사항: PLN-01, DOC-01  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | multipart/form-data; boundary는 클라이언트가 설정 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request Parts

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `file` | `binary` | Y | 원본 파일 |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 문서 유형 |
| `title` | `string` | Y | 제목 |
| `domain` | `string` | N | 담당 영역 |

file 파트는 실제 바이트, 나머지는 텍스트 파트로 보낸다.

```json
{
  "file": "(binary)",
  "kind": "REQUIREMENTS",
  "title": "요구사항 명세서",
  "domain": "account"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document` | [Document](00-common.md#schema-document) | Y | 저장된 원문과 DRAFT 버전 |
| `analysis_status` | `PENDING` | Y | 분석 접수 상태 |
| `review` | [DocumentReview](00-common.md#schema-documentreview) | Y | 비비 필수 검토 접수 상태(QUEUED). 수정안 요청 |

```json
{
  "document": {
    "id": "201",
    "project_id": "101",
    "kind": "REQUIREMENTS",
    "title": "요구사항 명세서",
    "domain": "account",
    "latest_draft": {
      "id": "211",
      "document_id": "201",
      "version_no": 1,
      "status": "DRAFT",
      "file_name": "document-201-v1.md",
      "media_type": "text/markdown",
      "content_hash": "sha256:example-document-hash",
      "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
      "created_at": "2026-10-23T03:00:00Z"
    }
  },
  "analysis_status": "PENDING"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 413 | `file_too_large` | 용량 초과 |
| 415 | `unsupported_media_type` | 지원하지 않는 형식 |
| 409 | `document_kind_exists` | 동일 종류 등록 제한(Q07). 복수 허용은 Q20 |

```json
{
  "error": {
    "code": "file_too_large",
    "message": "용량 초과"
  }
}
```

#### 처리 규칙

- 현재 팀장이 원문을 등록한다. 업로드 성공은 승인·분석 완료를 뜻하지 않는다. (수정안 요청: 팀원도 등록하고 팀장은 DOC-A21에서 승인만 한다. 지금 프론트는 팀원에게 버튼을 보여 403을 받는다.)
- document와 DRAFT document_version을 저장하고 분석을 접수한다.
- 파일 형식·용량·중복 등록은 이 절의 확정 업로드 정책을 따른다.
- (수정안 요청) 업로드와 같은 트랜잭션에서 비비 필수 검토(DOC-A13)를 접수한다. 검토를 끄거나 건너뛰는 옵션은 두지 않는다. 검토가 끝나기 전에는 팀장 승인 요청(DOC-A19)을 받지 않는다.
- (수정안 요청) 화면은 .md만 받는다. 정책의 .txt 허용은 그대로 두고 화면 문구를 맞춘다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a02"></a>

## DOC-A02. 문서 목록

### GET `/api/projects/{id}/documents`

문서 목록 기능을 제공한다.

관련 요구사항: DOC-02  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | N | 종류 |
| `domain` | `string` | N | 영역 |
| `stage` | `IN_PROGRESS\|CURRENT` | N | 진행 중(비비 검토·수정 제안 확인·승인 대기·반려됨) / 현재 기준 문서. 수정안 요청 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Document[]](00-common.md#schema-document) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "201",
      "project_id": "101",
      "kind": "REQUIREMENTS",
      "title": "요구사항 명세서",
      "domain": "account",
      "approved_version": {
        "id": "211",
        "document_id": "201",
        "version_no": 1,
        "status": "APPROVED",
        "file_name": "document-201-v1.md",
        "media_type": "text/markdown",
        "content_hash": "sha256:example-document-hash",
        "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
        "created_at": "2026-10-23T03:00:00Z"
      }
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.
- (수정안 요청) IN_PROGRESS는 latest_draft의 review_stage가 있는 문서, CURRENT는 현재 기준에 들어 있는 승인본이다. 승인되면 진행 중에서 빠지고 현재 문서로 옮겨진다.
- (수정안 요청) 각 문서에 `preview_text`(본문 앞부분 300자)를 넣어 썸네일 보기가 미리보기를 그린다. 이미지 썸네일은 나중이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a03"></a>

## DOC-A03. 문서 원문 조회

### GET `/api/projects/{id}/documents/{document_id}`

문서 원문 조회 기능을 제공한다.

관련 요구사항: DOC-03  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 문서 버전·형식 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `version_no` | `integer` | N | 명시할 문서 버전. 생략 시 현재 승인본 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document` | [Document](00-common.md#schema-document) | Y | 문서 |
| `version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | 선택한 버전 |

```json
{
  "document": {
    "id": "201",
    "project_id": "101",
    "kind": "REQUIREMENTS",
    "title": "요구사항 명세서",
    "domain": "account",
    "approved_version": {
      "id": "211",
      "document_id": "201",
      "version_no": 1,
      "status": "APPROVED",
      "file_name": "document-201-v1.md",
      "media_type": "text/markdown",
      "content_hash": "sha256:example-document-hash",
      "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
      "created_at": "2026-10-23T03:00:00Z"
    }
  },
  "version": {
    "id": "211",
    "document_id": "201",
    "version_no": 1,
    "status": "APPROVED",
    "file_name": "document-201-v1.md",
    "media_type": "text/markdown",
    "content_hash": "sha256:example-document-hash",
    "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
    "created_at": "2026-10-23T03:00:00Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 404 | `baseline_not_published` | 승인본 조회 시 현재 기준 없음 |

```json
{
  "error": {
    "code": "baseline_not_published",
    "message": "승인본 조회 시 현재 기준 없음"
  }
}
```

#### 처리 규칙

- 버전 생략 시 baseline_document에 포함된 현재 승인본만 반환한다.
- 초안을 승인본 대신 반환하지 않는다. 초안은 버전을 명시해 조회한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a04"></a>

## DOC-A04. 원본 파일 다운로드

### GET `/api/projects/{id}/documents/{document_id}/versions/{version_no}/file`

원본 파일 다운로드 기능을 제공한다.

관련 요구사항: DOC-03  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `version_no` | `integer` | Y | 리소스 안의 버전 번호 |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

원본 파일 스트림을 반환한다. JSON 본문이 아니다.

응답 헤더:

| 헤더 | 값·설명 |
| --- | --- |
| Content-Type | 원본 media_type |
| Content-Disposition | attachment; filename*=UTF-8''requirements.md |

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 원본 바이트를 스트림으로 반환한다. 저장소 경로·storage_key를 공개 URL로 노출하지 않는다.
- 프로젝트·문서·버전 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a05"></a>

## DOC-A05. 문서 버전 목록

### GET `/api/projects/{id}/documents/{document_id}/versions`

문서 버전 목록 기능을 제공한다.

관련 요구사항: DOC-07·09  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [DocumentVersion[]](00-common.md#schema-documentversion) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "211",
      "document_id": "201",
      "version_no": 1,
      "status": "APPROVED",
      "file_name": "document-201-v1.md",
      "media_type": "text/markdown",
      "content_hash": "sha256:example-document-hash",
      "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 과거 승인본·초안을 구분한다. 각 승인 버전 본문은 불변이다.
- 승인자·이유는 연결된 변경 제안과 승인 이력으로 확인한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a06"></a>

## DOC-A06. 문서 버전 비교

### GET `/api/projects/{id}/documents/{document_id}/diff`

문서 버전 비교 기능을 제공한다.

관련 요구사항: DOC-09, CHG-04  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from_version` | `integer` | N | 변경 전 문서 버전. compare=VERSIONS면 필수 |
| `to_version` | `integer` | Y | 변경 후 문서 버전 |
| `compare` | `VERSIONS\|UPLOAD_TO_REQUEST\|REJECTED_TO_REQUEST` | N | 비교 종류. 기본 VERSIONS(from→to). UPLOAD_TO_REQUEST는 to_version의 올린 원본→반영본, REJECTED_TO_REQUEST는 마지막 반려본→다시 요청한 버전. 수정안 요청 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from_version` | `integer` | Y | 변경 전 번호 |
| `to_version` | `integer` | Y | 변경 후 번호 |
| `format` | `TEXT\|FILE` | Y | 비교 종류 |
| `diff` | `string` | N | 텍스트 차이. 바이너리는 생략 |
| `lines` | [DiffLine[]](00-common.md#schema-diffline) | N | 줄 단위 비교. 텍스트면 필수. 수정안 요청 |
| `before_hash` | `string` | Y | 변경 전 해시 |
| `after_hash` | `string` | Y | 변경 후 해시 |

```json
{
  "from_version": 1,
  "to_version": 2,
  "format": "TEXT",
  "diff": "- 이전 설명\n+ 변경 설명",
  "before_hash": "sha256:before",
  "after_hash": "sha256:after"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 서버가 두 원문을 읽어 비교한다. 클라이언트의 before 본문을 신뢰하지 않는다.
- 바이너리는 버전·해시와 각 버전 다운로드로 비교한다.
- (수정안 요청) 팀장 승인 화면의 '변경 내용'은 lines로 그린다. 추가는 초록, 삭제는 빨강이고 비비 제안으로 바뀐 줄에는 분류(예: 비비 제안 · 누락)를 붙인다. 문서 상세의 버전 비교는 이 화면으로 옮겼다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a07"></a>

## DOC-A07. 문서 분류 수정

### PATCH `/api/projects/{id}/documents/{document_id}`

문서 분류 수정 기능을 제공한다.

관련 요구사항: DOC-04  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `domain` | `string` | Y | 변경할 영역 |

```json
{
  "domain": "account"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 문서 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | Y | 문서 유형 |
| `title` | `string` | Y | 문서 제목 |
| `domain` | `string` | N | 분류 |
| `approved_version` | [DocumentVersion](00-common.md#schema-documentversion) | N | 현재 기준의 승인본 |
| `latest_draft` | [DocumentVersion](00-common.md#schema-documentversion) | N | 최신 미승인 초안 |

```json
{
  "id": "201",
  "project_id": "101",
  "kind": "REQUIREMENTS",
  "title": "요구사항 명세서",
  "domain": "account",
  "approved_version": {
    "id": "211",
    "document_id": "201",
    "version_no": 1,
    "status": "APPROVED",
    "file_name": "document-201-v1.md",
    "media_type": "text/markdown",
    "content_hash": "sha256:example-document-hash",
    "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
    "created_at": "2026-10-23T03:00:00Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 분류 메타데이터만 수정한다. 승인 원문·본문은 변경 제안을 사용한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a01"></a>

## PLN-A01. 문서 분석 상태·결과 조회

2026-10-27 원문 RAG 구현: status는 전체 실행 상태다. source_status/index_status(PENDING/RUNNING/READY/FAILED), attempt, message를 추가한다. COMPLETED는 청킹·임베딩·청크/manifest 원자적 게시 완료다. 자동 추출은 후속이므로 items/questions는 []이며 미도입 EXTRACTION을 대기/실패로 표시하지 않는다. SOURCE 성공 뒤 임베딩 실패는 READY/FAILED로 구분한다. 진행 문구는 ‘분석 중입니다.’다. 아래 자동 추출 예시는 후속 기능이며 현재 원문 RAG 완료 조건과 구분한다.

### GET `/api/projects/{id}/documents/{document_id}/analysis`

문서 분석 상태·결과 조회 기능을 제공한다.

관련 요구사항: PLN-02~04  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 분석 원본 버전 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 분석 원본 버전 |
| `status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | 분석 상태 |
| `items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 추출 항목 초안 |
| `questions` | [PlanningQuestion[]](00-common.md#schema-planningquestion) | Y | 보완 질문 |
| `error_code` | `string` | N | 실패 원인 |

```json
{
  "document_version_id": "211",
  "status": "COMPLETED",
  "items": [
    {
      "id": "311",
      "design_item_id": "301",
      "key": "API-001",
      "kind": "API_CONTRACT",
      "version_no": 1,
      "status": "DRAFT",
      "schema_version": 1,
      "content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "이메일·비밀번호 로그인"
      },
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    }
  ],
  "questions": [
    {
      "id": "61",
      "question": "프로젝트 삭제 권한은 팀장만 가지나요?",
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      },
      "answer": "팀장만 삭제할 수 있습니다.",
      "answered_by": "11",
      "answered_at": "2026-10-23T03:00:00Z"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 실패 상태와 결과 0건을 구분한다. 분석 결과는 미승인 초안이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a02"></a>

## PLN-A02. 문서 분석 재시도

2026-10-27 원문 RAG 구현: FAILED만 새 attempt로 재접수한다. PENDING/RUNNING은 409 analysis_running, 완료 실행은 409 analysis_not_failed다. 같은 멱등 키는 같은 응답이다. 원본·사람 수정·승인 항목은 변경하지 않는다. lease 만료/서버 재시작 복구는 새 attempt/lease로 늦은 응답을 차단한다. 모델 미설정은 embedding_profile_not_configured, AI/출력 검증 실패는 상태 error_code로 기록한다.

lease 자동 복구는 최초 실행 이후 최대 2회(총 3회 실행)다. 예산 소진 후 다시 만료되면 FAILED / lease_recovery_exhausted로 종료하고 lease를 해제한다. 명시적 재시도는 자동 복구 횟수를 0으로 초기화하며 attempt는 계속 증가시킨다.

### POST `/api/projects/{id}/documents/{document_id}/analysis/retry`

문서 분석 재시도 기능을 제공한다.

관련 요구사항: PLN-02, NFR-06  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 실패한 원본 버전 |

```json
{
  "document_version_id": "211"
}
```

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 분석 원본 버전 |
| `status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | 분석 상태 |
| `items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 추출 항목 초안 |
| `questions` | [PlanningQuestion[]](00-common.md#schema-planningquestion) | Y | 보완 질문 |
| `error_code` | `string` | N | 실패 원인 |

```json
{
  "document_version_id": "211",
  "status": "RUNNING",
  "items": [],
  "questions": []
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `analysis_running` | 같은 버전 분석 중 |

```json
{
  "error": {
    "code": "analysis_running",
    "message": "같은 버전 분석 중"
  }
}
```

#### 처리 규칙

- 동일 원본을 대상으로 새 분석 시도를 접수한다. 실패 결과를 승인본에 반영하지 않는다.
- 원문·버전은 유지하며 분석 결과가 기존 사람 수정 초안을 덮어쓰지 않도록 Q07 정책을 확인한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a03"></a>

## PLN-A03. 기획 보완 질문 목록

### GET `/api/projects/{id}/planning-questions`

기획 보완 질문 목록 기능을 제공한다.

관련 요구사항: PLN-05  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | N | 원본 버전 필터 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [PlanningQuestion[]](00-common.md#schema-planningquestion) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "61",
      "question": "프로젝트 삭제 권한은 팀장만 가지나요?",
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      },
      "answer": "팀장만 삭제할 수 있습니다.",
      "answered_by": "11",
      "answered_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a04"></a>

## PLN-A04. 기획 보완 질문 답변

### PATCH `/api/projects/{id}/planning-questions/{question_id}`

기획 보완 질문 답변 기능을 제공한다.

관련 요구사항: PLN-05  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `question_id` | `ID` | Y | 보완 질문 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `answer` | `string` | Y | 답변 |

```json
{
  "answer": "팀장만 프로젝트를 삭제합니다."
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 질문 ID |
| `question` | `string` | Y | 보완 질문 |
| `source` | [Source](00-common.md#schema-source) | N | 출처 |
| `answer` | `string` | N | 답변 |
| `answered_by` | `ID` | N | 답변 계정 |
| `answered_at` | `Time` | N | 답변 시각 |

```json
{
  "id": "61",
  "question": "프로젝트 삭제 권한은 팀장만 가지나요?",
  "source": {
    "document_id": "201",
    "document_version_id": "211",
    "version_no": 1,
    "location": "3. 계정 관리 / ACC-02"
  },
  "answer": "팀장만 삭제할 수 있습니다.",
  "answered_by": "11",
  "answered_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `design_already_published` | 이미 발행된 초안 |

```json
{
  "error": {
    "code": "design_already_published",
    "message": "이미 발행된 초안"
  }
}
```

#### 처리 규칙

- 팀장이 답변을 저장한다. 답변 자체가 승인 설계 변경은 아니다.
- 초안 재생성은 PLN-A05, 발행은 PLN-A10을 사용한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a05"></a>

## PLN-A05. 답변을 반영한 설계 초안 생성

### POST `/api/projects/{id}/design-drafts`

답변을 반영한 설계 초안 생성 기능을 제공한다.

관련 요구사항: PLN-06  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_ids` | `ID[]` | Y | 분석된 원본 버전 |

```json
{
  "document_version_ids": [
    "211",
    "212",
    "213",
    "214"
  ]
}
```

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `PENDING` | Y | 접수 상태 |
| `document_version_ids` | `ID[]` | Y | 대상 원본 |

```json
{
  "status": "PENDING",
  "document_version_ids": [
    "211",
    "212",
    "213",
    "214"
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `analysis_not_completed` | 선행 분석 미완료 |

```json
{
  "error": {
    "code": "analysis_not_completed",
    "message": "선행 분석 미완료"
  }
}
```

#### 처리 규칙

- 선택한 원문·보완 답변으로 DRAFT 항목을 생성한다.
- 장시간 생성 결과는 PLN-A06으로 조회한다. 별도 job 상태 보관과 기존 초안 보존은 Q07이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a06"></a>

## PLN-A06. 설계 항목 목록

### GET `/api/projects/{id}/design-items`

설계 항목 목록 기능을 제공한다.

관련 요구사항: PLN-07  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `string` | N | 버전 상태. 기본 APPROVED, 초기 검토는 DRAFT |
| `kind` | `string` | N | 항목 종류 |
| `document_version_id` | `ID` | N | 원문 버전 필터 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |
| `draft_snapshot_hash` | `string` | N | 선택한 초안 전체의 발행 검토 해시. DRAFT 조회 시 반환 |

```json
{
  "items": [
    {
      "id": "311",
      "design_item_id": "301",
      "key": "API-001",
      "kind": "API_CONTRACT",
      "version_no": 1,
      "status": "DRAFT",
      "schema_version": 1,
      "content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "이메일·비밀번호 로그인"
      },
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      },
      "content_hash": "sha256:example-item"
    }
  ],
  "has_more": false,
  "draft_snapshot_hash": "sha256:reviewed-draft"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 페이지 필터에 맞는 항목을 반환한다. 기본 조회는 현재 baseline 항목이다.
- DRAFT 조회에서 페이지 cursor를 제외한 동일 선택 범위의 전체 스냅샷 해시를 제공한다. 최초 발행 전 다시 대조한다.
- 정확한 해시 대상·계산 방식은 Q08이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a07"></a>

## PLN-A07. 설계 항목 초안 추가

### POST `/api/projects/{id}/design-items`

설계 항목 초안 추가 기능을 제공한다.

관련 요구사항: PLN-07  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 항목 종류 |
| `schema_version` | `integer` | Y | 본문 구조 버전 |
| `content` | `object` | Y | 종류별 본문 Q01 |
| `source_document_version_id` | `ID` | N | 원문 버전 |
| `source_location` | `string` | N | 원문 위치 |

```json
{
  "kind": "POLICY",
  "schema_version": 1,
  "content": {
    "description": "팀장만 프로젝트를 삭제한다."
  },
  "source_document_version_id": "211",
  "source_location": "3. 권한"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 항목 버전 ID |
| `design_item_id` | `ID` | Y | 항목 ID |
| `key` | `string` | Y | 서버 발급 프로젝트 내 표시 키 |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 항목 종류 |
| `version_no` | `integer` | Y | 항목 버전 번호 |
| `status` | `DRAFT\|APPROVED\|SUPERSEDED\|REJECTED` | Y | 항목 버전 상태 |
| `schema_version` | `integer` | Y | 본문 스키마 버전 |
| `content` | `object` | Y | kind별 본문 구조. Q01 검토 대상 |
| `source` | [Source](00-common.md#schema-source) | N | 원문 출처 |
| `content_hash` | `string` | N | DRAFT 본문 경합 검사 해시 |

```json
{
  "id": "311",
  "design_item_id": "301",
  "key": "API-001",
  "kind": "POLICY",
  "version_no": 1,
  "status": "DRAFT",
  "schema_version": 1,
  "content": {
    "description": "팀장만 프로젝트를 삭제한다."
  },
  "source": {
    "document_id": "201",
    "document_version_id": "211",
    "version_no": 1,
    "location": "3. 계정 관리 / ACC-02"
  },
  "content_hash": "sha256:example-item"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- ID·표시 키는 서버가 발급하며 DRAFT로 저장한다.
- 승인 설계에 바로 추가하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a08"></a>

## PLN-A08. 설계 항목 초안 수정

### PATCH `/api/human/design-items/{id}`

설계 항목 초안 수정 기능을 제공한다.

관련 요구사항: PLN-07  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 설계 항목 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 프로젝트 |
| `expected_version_id` | `ID` | Y | 조회한 항목 버전 ID |
| `content` | `object` | Y | 수정할 본문 Q01 |
| `expected_content_hash` | `string` | Y | 같은 초안 버전 본문의 경합 확인 해시 |

```json
{
  "project_id": "101",
  "expected_version_id": "311",
  "content": {
    "description": "팀장만 프로젝트를 삭제한다."
  },
  "expected_content_hash": "sha256:old-draft"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 항목 버전 ID |
| `design_item_id` | `ID` | Y | 항목 ID |
| `key` | `string` | Y | 서버 발급 프로젝트 내 표시 키 |
| `kind` | `REQUIREMENT\|API_CONTRACT\|DATA_CONTRACT\|POLICY\|CONSTRAINT` | Y | 항목 종류 |
| `version_no` | `integer` | Y | 항목 버전 번호 |
| `status` | `DRAFT\|APPROVED\|SUPERSEDED\|REJECTED` | Y | 항목 버전 상태 |
| `schema_version` | `integer` | Y | 본문 스키마 버전 |
| `content` | `object` | Y | kind별 본문 구조. Q01 검토 대상 |
| `source` | [Source](00-common.md#schema-source) | N | 원문 출처 |
| `content_hash` | `string` | N | DRAFT 본문 경합 검사 해시 |

```json
{
  "id": "311",
  "design_item_id": "301",
  "key": "API-001",
  "kind": "API_CONTRACT",
  "version_no": 1,
  "status": "DRAFT",
  "schema_version": 1,
  "content": {
    "description": "팀장만 프로젝트를 삭제한다."
  },
  "source": {
    "document_id": "201",
    "document_version_id": "211",
    "version_no": 1,
    "location": "3. 계정 관리 / ACC-02"
  },
  "content_hash": "sha256:example-item"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 버전·본문 경합 또는 승인본 수정 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "버전·본문 경합 또는 승인본 수정"
  }
}
```

#### 처리 규칙

- 미발행 DRAFT만 수정한다. 승인·과거 버전의 본문은 수정 불가다.
- 버전 ID가 같은 초안 동시 수정도 놓치지 않도록 본문 해시를 대조한다. 저장/해시 조회 세부는 Q08이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a09"></a>

## PLN-A09. 설계 항목 초안 제외

### DELETE `/api/human/design-items/{id}`

설계 항목 초안 제외 기능을 제공한다.

관련 요구사항: PLN-07  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 설계 항목 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 프로젝트 |
| `expected_version_id` | `ID` | Y | 대상 초안 버전 |
| `expected_content_hash` | `string` | Y | 초안 경합 해시 |

#### Request

없음.
#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `version_conflict` | 승인본·경합·참조 충돌 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "승인본·경합·참조 충돌"
  }
}
```

#### 처리 규칙

- 초기 발행 대상에서 초안을 제외한다. 물리 삭제하지 않는다.
- 기존 승인 항목 폐기는 변경 제안의 RETIRE를 사용한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a10"></a>

## PLN-A10. 최초 기준 설계 승인·발행

### POST `/api/human/baselines`

최초 기준 설계 승인·발행 기능을 제공한다.

관련 요구사항: PLN-08·09, DOC-08  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | Y | 프로젝트 |
| `document_version_ids` | `ID[]` | Y | 검토한 문서 4종 버전 |
| `design_item_version_ids` | `ID[]` | Y | 검토한 항목 버전 |
| `draft_snapshot_hash` | `string` | Y | 검토한 전체 초안 해시 |

```json
{
  "project_id": "101",
  "document_version_ids": [
    "211",
    "212",
    "213",
    "214"
  ],
  "design_item_version_ids": [
    "311",
    "312"
  ],
  "draft_snapshot_hash": "sha256:reviewed-draft"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기준 설계 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `version_no` | `integer` | Y | 프로젝트 내 기준 번호 |
| `documents` | [DocumentRef[]](00-common.md#schema-documentref) | Y | 정확한 발행 문서 버전 |
| `items` | [VersionRef[]](00-common.md#schema-versionref) | Y | 정확한 발행 항목 버전 |
| `approved_by` | `ID` | Y | 승인한 사람 계정 |
| `created_at` | `Time` | Y | 발행 시각 |

```json
{
  "id": "401",
  "project_id": "101",
  "version_no": 1,
  "documents": [
    {
      "document_id": "201",
      "document_version_id": "211"
    }
  ],
  "items": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ],
  "approved_by": "11",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_exists` | 이미 최초 발행됨 |
| 409 | `draft_changed` | 검토 후 초안 변경 |
| 400 | `required_documents_missing` | 필수 문서 누락 |

```json
{
  "error": {
    "code": "baseline_exists",
    "message": "이미 최초 발행됨"
  }
}
```

#### 처리 규칙

- 현재 사람 팀장의 권한과 문서·항목 소속을 검사한다.
- 프로젝트 잠금 후 현재 baseline 부재·초안 해시·필수 문서 4종·분석 상태를 확인한다.
- 문서/항목 승인, baseline_item·baseline_document, 현재 baseline 포인터를 한 트랜잭션으로 발행한다.
- 이후 변경은 변경 제안 승인 API로 처리한다.
- (수정안 요청) 수정안은 문서별 팀장 승인(DOC-A21)으로 필수 4종이 모두 APPROVED가 되는 트랜잭션에서 서버가 기준 v1을 발행한다. 설계 항목은 승인 문서에서 자동 추출한 항목을 그대로 넣는 것이 기본안이다(Q15). 화면이 이 API를 직접 부르지 않게 되면 내부 발행 함수로 남긴다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a08"></a>

## DOC-A08. 현재 기준 설계 조회

### GET `/api/projects/{id}/baselines/current`

현재 기준 설계 조회 기능을 제공한다.

관련 요구사항: DOC-08, MON-01  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기준 설계 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `version_no` | `integer` | Y | 프로젝트 내 기준 번호 |
| `documents` | [DocumentRef[]](00-common.md#schema-documentref) | Y | 정확한 발행 문서 버전 |
| `items` | [VersionRef[]](00-common.md#schema-versionref) | Y | 정확한 발행 항목 버전 |
| `approved_by` | `ID` | Y | 승인한 사람 계정 |
| `created_at` | `Time` | Y | 발행 시각 |

```json
{
  "id": "401",
  "project_id": "101",
  "version_no": 1,
  "documents": [
    {
      "document_id": "201",
      "document_version_id": "211"
    }
  ],
  "items": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ],
  "approved_by": "11",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 404 | `baseline_not_published` | 아직 발행하지 않음 |

```json
{
  "error": {
    "code": "baseline_not_published",
    "message": "아직 발행하지 않음"
  }
}
```

#### 처리 규칙

- project.current_baseline_id를 통해 항목·문서의 정확한 스냅샷을 조회한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a09"></a>

## DOC-A09. 기준 설계 이력 목록

### GET `/api/projects/{id}/baselines`

기준 설계 이력 목록 기능을 제공한다.

관련 요구사항: DOC-07·08  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [BaselineSummary[]](00-common.md#schema-baselinesummary) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "401",
      "version_no": 1,
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a10"></a>

## DOC-A10. 기준 설계 특정 버전 조회

### GET `/api/projects/{id}/baselines/{version_no}`

기준 설계 특정 버전 조회 기능을 제공한다.

관련 요구사항: DOC-07·08  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_no` | `integer` | Y | 리소스 안의 버전 번호 |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기준 설계 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `version_no` | `integer` | Y | 프로젝트 내 기준 번호 |
| `documents` | [DocumentRef[]](00-common.md#schema-documentref) | Y | 정확한 발행 문서 버전 |
| `items` | [VersionRef[]](00-common.md#schema-versionref) | Y | 정확한 발행 항목 버전 |
| `approved_by` | `ID` | Y | 승인한 사람 계정 |
| `created_at` | `Time` | Y | 발행 시각 |

```json
{
  "id": "401",
  "project_id": "101",
  "version_no": 1,
  "documents": [
    {
      "document_id": "201",
      "document_version_id": "211"
    }
  ],
  "items": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ],
  "approved_by": "11",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- version_no는 baseline ID가 아니라 프로젝트 내 번호다. 과거 스냅샷을 현재 내용으로 대체하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a11"></a>

## DOC-A11. 문서·설계 검색

SEMANTIC 검색은 프로젝트·현재 baseline의 정확한 승인 버전·embedding profile·domain·kind로 허용 청크를 MATERIALIZED CTE에 확정한 뒤 거리순 정확 검색을 수행한다. 전역 HNSW 후보의 사후 필터링에 의존하지 않는다. 동일 거리에서는 청크 ID로 정렬하고, 검색 전후 baseline이 바뀌면 baseline_changed로 거절한다.

### GET `/api/projects/{id}/search`

문서·설계 검색 기능을 제공한다.

관련 요구사항: DOC-11·12  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `q` | `string` | Y | 검색어 |
| `mode` | `KEYWORD\|SEMANTIC` | Y | 검색 방식 |
| `domain` | `string` | N | 영역 |
| `kind` | `string` | N | 문서 또는 항목 종류 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [SearchHit[]](00-common.md#schema-searchhit) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |
| `elapsed_ms` | `integer` | Y | 서버 검색 소요 ms |

```json
{
  "items": [
    {
      "type": "DESIGN_ITEM",
      "id": "301",
      "title": "로그인 계약",
      "snippet": "이메일과 비밀번호를 받는다.",
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      },
      "design_item_id": "301",
      "score": 0.91
    }
  ],
  "has_more": false,
  "elapsed_ms": 120
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 502 | `ai_unavailable` | 의미 검색 임베딩 장애 |
| 504 | `ai_timeout` | 검색 모델 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "의미 검색 임베딩 장애"
  }
}
```

#### 처리 규칙

- 현재 승인본만 검색하고 권한·프로젝트 필터를 검색 전에 적용한다.
- BE 키워드·벡터 저장/검색·실행/manifest는 오서현, 청킹·임베딩·검색 전략 AI는 박상은 담당이다. KEYWORD는 현재 baseline의 문서/항목을 검색해 DOCUMENT/DESIGN_ITEM을, SEMANTIC은 READY 청크의 DOCUMENT_CHUNK를 반환한다. 원문 키워드 검색은 색인과 독립적이다. 출처 없는 HUMAN 승인 항목은 source를 생략한다.
- 검색 실패를 빈 결과로 표시하지 않는다. 유사도와 승인 근거를 구분한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="trc-a01"></a>

## TRC-A01. 설계 항목 의존관계 조회

### GET `/api/projects/{id}/design-items/{item_id}/dependencies`

설계 항목 의존관계 조회 기능을 제공한다.

관련 요구사항: DOC-13, TRC-01·06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `item_id` | `ID` | Y | 설계 항목 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [DesignDependency[]](00-common.md#schema-designdependency) | Y | 확정 관계 |
| `candidates` | [DependencyCandidate[]](00-common.md#schema-dependencycandidate) | Y | 미확정 후보 |

```json
{
  "items": [
    {
      "from_design_item_id": "301",
      "to_design_item_id": "302",
      "relation": "DEPENDS_ON"
    }
  ],
  "candidates": [
    {
      "from_design_item_id": "301",
      "to_design_item_id": "302",
      "reason": "동일 데이터 필드를 사용합니다.",
      "confirmed": false
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 확정 관계와 추천 근거는 구분한다. 단순 조회로 관계를 확정하지 않는다.
- 확정 관계 변경은 변경 제안으로 처리하며 작업 관계는 계획 리비전을 통해 관리한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a12"></a>

## DOC-A12. 변경용 원문 파일 업로드

DOC-A01의 확정 입력·저장·공통 세션/요청 출처·멱등 정책을 적용한다. base_document_version_id는 같은 문서의 현재 baseline에 속한 정확한 승인 버전이어야 한다. 승인본이 없거나 오래된 기준이면 409 version_conflict. 새 DRAFT/영속 분석 작업을 원자적으로 생성하고 기존 승인 원본을 보존한다. file_name은 생성 다운로드 이름이다.

화면의 ‘수정 초안 작성’은 현재 승인본을 조회할 때만 활성화한다. 최초 승인 전에는 제한 사유를 표시하고, 과거 버전 또는 DRAFT 조회 중에는 현재 승인본으로 이동할 수 있게 한다. 편집 중 baseline이 변경되면 서버의 version_conflict 검사를 그대로 적용한다.

### POST `/api/projects/{id}/documents/{document_id}/draft-versions`

변경용 원문 파일 업로드 기능을 제공한다.

관련 요구사항: DOC-06·07, CHG-02  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | multipart/form-data; boundary는 클라이언트가 설정 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

없음.

#### Request Parts

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `file` | `binary` | Y | 변경할 원본 |
| `base_document_version_id` | `ID` | Y | 기준 원문 |

file 파트는 실제 바이트, 나머지는 텍스트 파트로 보낸다.

```json
{
  "file": "(binary)",
  "base_document_version_id": "211"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 문서 버전 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `version_no` | `integer` | Y | 문서 내 버전 번호 |
| `status` | `DRAFT\|APPROVED\|SUPERSEDED\|REJECTED` | Y | 문서 버전 상태 |
| `file_name` | `string` | Y | 생성 다운로드명 |
| `media_type` | `string` | Y | MIME |
| `content_hash` | `string` | Y | 원본 해시 |
| `content` | `string` | N | 텍스트 본문. 바이너리는 생략 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "212",
  "document_id": "201",
  "version_no": 2,
  "status": "DRAFT",
  "file_name": "document-201-v1.md",
  "media_type": "text/markdown",
  "content_hash": "sha256:example-document-hash",
  "content": "# 요구사항\n로그인한 사용자는 프로젝트를 조회한다.",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 기준 원문이 현재 승인본과 다름 |
| 413 | `file_too_large` | 용량 초과 |
| 415 | `unsupported_media_type` | 형식 불가 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "기준 원문이 현재 승인본과 다름"
  }
}
```

#### 처리 규칙

- 새 파일을 DRAFT로 저장하고 변경 제안에 연결할 버전 ID를 반환한다.
- 현재 승인본은 바뀌지 않는다. 사용되지 않은 업로드의 정리 기준은 Q07이다.
- (수정안 요청) 새 버전도 DOC-A01처럼 등록 즉시 비비 필수 검토를 접수하고 공용 문서함 승인 흐름(DOC-A13~A23)을 탄다. 수정안은 변경 제안 없이 문서함에서 승인한다(Q14).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a13"></a>

## DOC-A13. 비비 검토 상태

### GET `/api/projects/{id}/document-versions/{version_id}/review`

등록한 문서 버전의 비비 필수 검토 진행 상태를 돌려준다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-running-hh4x)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_version_id` | `ID` | Y | 검토 대상 버전 |
| `mode` | `REVIEW\|RERUN` | Y | 등록 직후 검토 / 반려 후 재검토 |
| `status` | `QUEUED\|RUNNING\|COMPLETED\|FAILED` | Y | 실행 상태 |
| `step` | `integer` | Y | 지금 단계. 1부터 |
| `total_steps` | `integer` | Y | 전체 단계 수 |
| `step_label` | `string` | Y | 단계 이름 |
| `progress_pct` | `integer` | Y | 0~100 |
| `eta_seconds` | `integer` | N | 남은 시간 추정 |
| `counts` | `object` | N | COMPLETED면 {major, minor} |
| `open_count` | `integer` | Y | 아직 처리하지 않은 제안 수 |
| `applied_count` | `integer` | Y | 반영한 제안 수 |
| `steps` | `object[]` | Y | 단계별 {label, description, result}. 예: '문서 읽기 · 문단 4개' |
| `error_code` | `string` | N | FAILED 원인 |

```json
{
  "document_version_id": "212",
  "mode": "REVIEW",
  "status": "RUNNING",
  "step": 3,
  "total_steps": 5,
  "step_label": "ERD와 맞춰 보기",
  "progress_pct": 58,
  "eta_seconds": 5,
  "open_count": 0
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- DOC-A01·A12 등록과 같은 트랜잭션에서 접수한 검토다. 화면은 몇 초 간격으로 읽어 'n단계 / 5단계', '% · 약 n초 남음'을 그린다.
- 창을 닫아도 검토는 계속되고, 끝나면 등록자에게 DOCUMENT_VERSION 알림을 보낸다.
- REVIEW 단계: 문서 읽기 → 승인된 API 명세서와 비교 → ERD와 맞춰 보기 → 요구사항과 비교 → 수정 제안 정리. 문서 종류에 따라 비교 대상이 바뀌고, 기준이 없으면 함께 올린 초안과 비교한다(Q19).
- RERUN 단계: 반려 사유 읽기 → 관련 문단 찾기 → ERD와 맞춰 보기 → 수정 제안 정리.
- 끝나면 버전의 review_stage가 SUGGESTIONS_PENDING(제안 있음) 또는 READY_TO_REQUEST(제안 없음)가 된다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a14"></a>

## DOC-A14. 비비 검토 재시도

### POST `/api/projects/{id}/document-versions/{version_id}/review/retry`

실패한 비비 검토를 다시 접수한다.

관련 요구사항: DOC-01  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `review` | [DocumentReview](00-common.md#schema-documentreview) | Y | 다시 접수한 검토 |

```json
{
  "review": {
    "document_version_id": "212",
    "mode": "REVIEW",
    "status": "QUEUED",
    "step": 1,
    "total_steps": 5,
    "step_label": "ERD와 맞춰 보기",
    "progress_pct": 0,
    "eta_seconds": 5,
    "open_count": 0
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `invalid_transition` | 검토가 FAILED가 아님 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- FAILED일 때만 같은 버전의 검토를 다시 접수한다.
- 재시도 한도와 팀장 우회 여부는 Q19다. 기본안은 우회 없음이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a15"></a>

## DOC-A15. 비비 수정 제안 목록

### GET `/api/projects/{id}/document-versions/{version_id}/review/suggestions`

비비가 문단에 남긴 수정 제안과 지금 반영본을 돌려준다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `OPEN\|APPLIED\|SKIPPED` | N | 처리 상태 필터 |

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ReviewSuggestion[]](00-common.md#schema-reviewsuggestion) | Y | 제안. locator 순서 |
| `content` | `string` | Y | 지금 반영본 전체 |
| `content_hash` | `string` | Y | 반영본 해시. DOC-A16·A19의 충돌 검사에 씀 |

```json
{
  "items": [
    {
      "id": "9001",
      "locator": {
        "type": "text_lines",
        "start_line": 12,
        "end_line": 14
      },
      "category": "OMISSION",
      "severity": "MAJOR",
      "reason": "요구사항 REQ-007의 읽음 처리 조건이 빠졌습니다.",
      "original_text": "알림 목록을 조회한다.",
      "proposed_text": "알림 목록을 조회하고, 열람한 알림은 읽음으로 표시한다.",
      "status": "OPEN",
      "origin": "REVIEW",
      "sources": [],
      "messages": []
    }
  ],
  "content": "# 알림 API 명세서\n…",
  "content_hash": "sha256:example"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `review_not_done` | 검토가 아직 끝나지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- ② 화면은 제안이 달린 문단 왼쪽 말풍선, 'N개 제안 중 n', 이전/다음을 이 목록으로 그린다. 요약의 Major·Minor 수는 DOC-A13 counts다.
- 각 제안의 messages에 비비와의 대화가 들어 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a16"></a>

## DOC-A16. 수정 제안 반영·넘기기

### PATCH `/api/projects/{id}/document-versions/{version_id}/review/suggestions/{suggestion_id}`

제안을 반영하거나 넘기거나 되돌린다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |
| `suggestion_id` | `ID` | Y | 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `APPLIED\|SKIPPED\|OPEN` | Y | 반영·넘기기. OPEN은 되돌리기 |
| `proposed_text` | `string` | N | 대화(DOC-A17)에서 받은 다른 문장으로 반영할 때 |
| `expected_content_hash` | `string` | Y | 조회한 반영본 해시 |

```json
{
  "status": "APPLIED",
  "expected_content_hash": "sha256:example"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `suggestion` | [ReviewSuggestion](00-common.md#schema-reviewsuggestion) | Y | 갱신한 제안 |
| `content` | `string` | Y | 갱신한 반영본 |
| `content_hash` | `string` | Y | 반영본 해시 |
| `review` | [DocumentReview](00-common.md#schema-documentreview) | Y | open_count가 갱신된 검토 |

```json
{
  "suggestion": {
    "id": "9001",
    "locator": {
      "type": "text_lines",
      "start_line": 12,
      "end_line": 14
    },
    "category": "OMISSION",
    "severity": "MAJOR",
    "reason": "요구사항 REQ-007의 읽음 처리 조건이 빠졌습니다.",
    "original_text": "알림 목록을 조회한다.",
    "proposed_text": "알림 목록을 조회하고, 열람한 알림은 읽음으로 표시한다.",
    "status": "APPLIED",
    "origin": "REVIEW",
    "sources": [],
    "messages": []
  },
  "content": "# 알림 API 명세서\n…",
  "content_hash": "sha256:example2",
  "review": {
    "document_version_id": "212",
    "mode": "REVIEW",
    "status": "COMPLETED",
    "step": 5,
    "total_steps": 5,
    "step_label": "ERD와 맞춰 보기",
    "progress_pct": 100,
    "eta_seconds": 5,
    "open_count": 2
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | expected_content_hash가 현재와 다름 |
| 409 | `invalid_transition` | 승인 요청 뒤라 바꿀 수 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- APPLIED면 그 문단을 제안 문장으로 바꾼 반영본을 저장한다. 화면은 바뀐 문단을 초록으로 보이고 되돌리기를 둔다.
- OPEN으로 되돌리면 그 문단을 원문으로 돌린다. 올린 원본은 바꾸지 않는다(DOC-A06 UPLOAD_TO_REQUEST 비교 기준).
- 모든 제안이 APPLIED·SKIPPED면 review_stage가 READY_TO_REQUEST가 되고 화면의 '완료'가 켜진다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a17"></a>

## DOC-A17. 수정 제안에 대해 비비와 대화

### POST `/api/projects/{id}/document-versions/{version_id}/review/suggestions/{suggestion_id}/messages`

제안 카드 아래 채팅으로 그 제안에 대해 비비와 상의한다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |
| `suggestion_id` | `ID` | Y | 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 비비에게 보낼 말 |

```json
{
  "text": "읽음 처리 대신 확인 시각을 남기는 건 어때?"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `message` | [ReviewMessage](00-common.md#schema-reviewmessage) | Y | 저장한 내 메시지 |
| `reply` | [ReviewMessage](00-common.md#schema-reviewmessage) | Y | 비비의 답. 다른 제안 문장이면 proposed_text |

```json
{
  "message": {
    "id": "9101",
    "author_kind": "HUMAN",
    "text": "읽음 처리 대신 확인 시각을 남기는 건 어때?",
    "created_at": "2026-10-28T03:00:00Z"
  },
  "reply": {
    "id": "9102",
    "author_kind": "BEE",
    "text": "확인 시각도 요구사항과 맞습니다. 이렇게 바꿀 수 있어요.",
    "proposed_text": "알림 목록을 조회하고, 열람한 알림에는 확인 시각을 기록한다.",
    "created_at": "2026-10-28T03:00:02Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | AI 응답 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 비비가 제안의 이유·근거를 설명하거나 다른 문장을 낸다(AI-A10 DISCUSS).
- 다른 문장으로 반영하려면 DOC-A16에 proposed_text를 함께 보낸다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a18"></a>

## DOC-A18. 비비에게 직접 수정 요청

### POST `/api/projects/{id}/document-versions/{version_id}/review/requests`

본문 아래 '비비에게 직접 요청하기'로 새 수정 제안을 받는다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 요청 내용 |
| `locator` | [SourceLocator](00-common.md#schema-sourcelocator) | N | 특정 문단을 가리킬 때 |

```json
{
  "text": "오류 코드 표를 맨 아래로 옮겨 줘."
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `suggestion` | [ReviewSuggestion](00-common.md#schema-reviewsuggestion) | Y | origin=DIRECT_REQUEST인 새 제안 |

```json
{
  "suggestion": {
    "id": "9001",
    "locator": {
      "type": "text_lines",
      "start_line": 12,
      "end_line": 14
    },
    "category": "READABILITY",
    "severity": "MINOR",
    "reason": "요구사항 REQ-007의 읽음 처리 조건이 빠졌습니다.",
    "original_text": "알림 목록을 조회한다.",
    "proposed_text": "알림 목록을 조회하고, 열람한 알림은 읽음으로 표시한다.",
    "status": "OPEN",
    "origin": "DIRECT_REQUEST",
    "sources": [],
    "messages": []
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `invalid_transition` | 승인 요청 뒤 |
| 502 | `ai_unavailable` | AI 응답 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 비비가 요청을 반영한 제안을 만든다(AI-A10 REQUEST). 새 제안도 처리해야 '완료'가 켜진다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a19"></a>

## DOC-A19. 팀장 승인 요청

### POST `/api/projects/{id}/document-versions/{version_id}/approval-request`

③ 최종본 확인에서 '팀장에게 검토 요청'을 보낸다.

관련 요구사항: DOC-01, DOC-08 (화면 PNL-02-m-review-confirm-x7fm)  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_content_hash` | `string` | Y | 확인한 반영본 해시 |

```json
{
  "expected_content_hash": "sha256:example2"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | review_stage=AWAITING_APPROVAL인 버전 |
| `is_resubmission` | `boolean` | Y | 반려 뒤 다시 요청한 것인지 |

```json
{
  "version": {
    "id": "212",
    "document_id": "201",
    "version_no": 2,
    "status": "DRAFT",
    "review_stage": "AWAITING_APPROVAL",
    "requested_by": "12",
    "requested_at": "2026-10-28T03:00:00Z",
    "is_current_baseline": false
  },
  "is_resubmission": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `review_not_done` | 비비 검토 중이거나 처리하지 않은 제안이 남음 |
| 409 | `version_conflict` | 반영본이 바뀜 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 반영본을 요청본으로 고정하고 팀장에게 DOCUMENT_VERSION 알림을 보낸다. 팀장의 '지금 할 일'에 '문서 승인 요청'으로 뜬다.
- 비비 검토가 끝나기 전이나 OPEN 제안이 있으면 거절한다. 검토를 건너뛰는 경로는 없다.
- 반려 뒤 다시 요청하면 is_resubmission=true이고 팀장 화면은 '반려 사유를 반영해 다시 요청'을 보인다.
- 요청 뒤에는 제안을 바꿀 수 없다. 고치려면 DOC-A20으로 요청을 취소한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a20"></a>

## DOC-A20. 승인 요청 취소

### DELETE `/api/projects/{id}/document-versions/{version_id}/approval-request`

팀장 승인 대기를 취소하고 최종본 확인 전 상태로 돌린다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-confirm-x7fm)  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `invalid_transition` | 승인 대기가 아님 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- AWAITING_APPROVAL을 READY_TO_REQUEST로 돌린다.
- 팀장에게 간 알림은 남지만 열면 지금 상태를 보인다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a21"></a>

## DOC-A21. 문서 버전 승인

### POST `/api/projects/{id}/document-versions/{version_id}/approve`

팀장이 요청본을 승인해 현재 문서로 만든다.

관련 요구사항: DOC-08, PLN-08 (화면 PNL-02-m-review-requested-1kri, PNL-02-m-list-lead-approved-53ka)  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_content_hash` | `string` | Y | 팀장이 본 요청본 해시 |

```json
{
  "expected_content_hash": "sha256:example2"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | APPROVED 버전 |
| `baseline_created` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 이 승인으로 기준 v1이 생겼으면 |

```json
{
  "version": {
    "id": "212",
    "document_id": "201",
    "version_no": 2,
    "status": "APPROVED",
    "review_stage": "APPROVED",
    "requested_by": "12",
    "requested_at": "2026-10-28T03:00:00Z",
    "is_current_baseline": true
  },
  "baseline_created": {
    "id": "301",
    "version_no": 1
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `review_not_done` | 승인 대기가 아님 |
| 409 | `version_conflict` | 요청본이 바뀜 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 완료 화면 없이 목록으로 돌아간다. 같은 문서의 이전 승인본은 SUPERSEDED가 된다. 올린 사람에게 알림을 보낸다.
- 필수 4종이 모두 APPROVED가 되는 트랜잭션에서 기준 v1을 발행하고 활성 팀원에게 BASELINE 알림을 보낸다. 설계 항목은 Q15다.
- 기준 v1 이후의 승인이 기준 버전을 올리는지, 관련 검증·작업을 재검증으로 돌리는지는 Q16이다.
- 문서 승인 경로를 변경 제안에 얹을지는 Q14다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a22"></a>

## DOC-A22. 문서 버전 반려

### POST `/api/projects/{id}/document-versions/{version_id}/reject`

팀장이 사유를 적어 요청본을 반려한다.

관련 요구사항: DOC-08 (화면 PNL-02-m-review-rejecting-doy8, PNL-02-m-list-lead-rejected-hh0o)  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `reason` | `string` | Y | 반려 사유. 공백만이면 400 |

```json
{
  "reason": "오류 코드 표에 410 응답이 빠졌습니다."
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `version` | [DocumentVersion](00-common.md#schema-documentversion) | Y | review_stage=REJECTED, last_rejection이 채워진 버전 |

```json
{
  "version": {
    "id": "212",
    "document_id": "201",
    "version_no": 2,
    "status": "DRAFT",
    "review_stage": "REJECTED",
    "requested_by": "12",
    "requested_at": "2026-10-28T03:00:00Z",
    "is_current_baseline": false,
    "last_rejection": {
      "id": "61",
      "reason": "오류 코드 표에 410 응답이 빠졌습니다.",
      "rejected_by": "11",
      "rejected_at": "2026-10-28T03:00:00Z",
      "requested_content_hash": "sha256:example2"
    }
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `review_not_done` | 승인 대기가 아님 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 어느 문단인지는 고르지 않는다. 재검토(DOC-A23) 때 비비가 사유를 읽고 관련 문단을 찾는다.
- 반려된 요청본의 해시를 남겨 다시 요청할 때 '반려본 → 재요청본' 비교 기준으로 쓴다.
- 올린 사람에게 사유와 함께 알림을 보낸다. 목록의 '진행 중' 카드는 '반려됨'이 된다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a23"></a>

## DOC-A23. 반려 후 비비 재검토

### POST `/api/projects/{id}/document-versions/{version_id}/review/rerun`

⑥ 반려됨에서 '비비에게 검토 요청'으로 반려 사유를 반영한 제안을 받는다.

관련 요구사항: DOC-08 (화면 PNL-02-m-review-rerun-em89, PNL-02-m-review-resuggest-lwej)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `version_id` | `ID` | Y | 문서 버전 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `review` | [DocumentReview](00-common.md#schema-documentreview) | Y | mode=RERUN인 검토 |

```json
{
  "review": {
    "document_version_id": "212",
    "mode": "RERUN",
    "status": "QUEUED",
    "step": 1,
    "total_steps": 4,
    "step_label": "반려 사유 읽기",
    "progress_pct": 0,
    "eta_seconds": 5,
    "open_count": 0
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `invalid_transition` | 반려 상태가 아님 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 마지막 반려 사유를 입력으로 재검토한다(AI-A10 RERUN). 결과 제안은 category=REJECTION_FIX이고 rejection_id를 가진다.
- 반영하면 ③ 최종본 확인을 거쳐 DOC-A19로 다시 요청한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a01"></a>

## CHG-A01. 설계 변경 제안 생성

### POST `/api/projects/{id}/change-proposals`

설계 변경 제안 생성 기능을 제공한다.

관련 요구사항: CHG-01·02  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `meeting_id` | `ID` | N | 관련 회의. 수정안 요청 |
| `previous_proposal_id` | `ID` | N | 반려된 제안을 고쳐 새로 올릴 때 이전 제안. 수정안 요청 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |

```json
{
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ]
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED\|WITHDRAWN` | Y | 제안 상태 |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |
| `meeting_id` | `ID` | N | 관련 회의. 수정안 요청 |
| `previous_proposal_id` | `ID` | N | 이전 제안. 수정안 요청 |
| `agent` | `object` | N | AGENT 제출이면 {agent_session_id, name, member_id}. 수정안 요청 |

```json
{
  "id": "71",
  "project_id": "101",
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "status": "PROPOSED",
  "proposer_account_id": "11",
  "actor_type": "HUMAN",
  "current_baseline_id": "401",
  "requires_refresh": false,
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_conflict` | 기준 baseline이 현재와 다름 |
| 409 | `baseline_required` | 기준 v1 발행 전 |

```json
{
  "error": {
    "code": "baseline_conflict",
    "message": "기준 baseline이 현재와 다름"
  }
}
```

#### 처리 규칙

- items/documents 중 하나 이상 변경이 필요하다. UPDATE/RETIRE의 기준 버전 소속을 검사한다.
- 문서 변경의 after_content와 proposed_document_version_id는 둘 중 하나다.
- 명시 관계의 영향 대상·알림을 먼저 처리하고 AI 심층 영향 분석은 별도로 실행한다.
- (수정안 요청) 설계 항목은 ChangeItem.proposed_text 문장 하나로 받을 수 있고 구조는 서버가 채운다(Q01). 화면은 documents를 보내지 않는다. 문서 새 버전은 공용 문서함에서 올린다(DOC-A12).
- (수정안 요청) 기준 v1 발행 전에는 409 baseline_required다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a02"></a>

## CHG-A02. 설계 변경 제안 목록

### GET `/api/projects/{id}/change-proposals`

설계 변경 제안 목록 기능을 제공한다.

관련 요구사항: CHG-07  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED\|WITHDRAWN` | N | 상태 필터 |
| `scope` | `DESIGN_ITEMS\|DOCUMENTS` | N | 설계 항목 제안만 / 문서 제안만. 변경 제안 창은 DESIGN_ITEMS. 수정안 요청 |
| `group` | `IN_PROGRESS\|PUBLISHED\|REJECTED` | N | 목록 탭 '진행 중 · 발행됨 · 반려'. 진행 중은 PROPOSED·IN_REVIEW·APPROVED. 탭마다 개수가 필요하면 MON-C01을 씀. 수정안 요청 |
| `q` | `string` | N | 제목·항목 번호 검색어. 수정안 요청 |
| `sort` | `latest\|status` | N | 최신순(기본)·상태순. 수정안 요청 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ChangeProposal[]](00-common.md#schema-changeproposal) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "71",
      "project_id": "101",
      "base_baseline_id": "401",
      "title": "로그인 오류 응답 설명 보완",
      "reason": "오류 표시 기준을 명확하게 한다.",
      "task_id": "501",
      "validation_id": "601",
      "items": [
        {
          "operation": "UPDATE",
          "design_item_id": "301",
          "base_design_item_version_id": "311",
          "schema_version": 1,
          "proposed_content": {
            "method": "POST",
            "path": "/api/auth/login",
            "description": "수정된 로그인 계약"
          }
        }
      ],
      "documents": [
        {
          "document_id": "201",
          "base_document_version_id": "211",
          "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
        }
      ],
      "status": "PROPOSED",
      "proposer_account_id": "11",
      "actor_type": "HUMAN",
      "current_baseline_id": "401",
      "requires_refresh": false,
      "created_at": "2026-10-23T03:00:00Z",
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.
- (수정안 요청) 목록은 한 줄 요약이고 상세는 개요·바뀌는 내용·영향·의견 탭에서 CHG-A03·A05·A13으로 읽는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a03"></a>

## CHG-A03. 설계 변경 제안 상세·비교

### GET `/api/projects/{id}/change-proposals/{proposal_id}`

설계 변경 제안 상세·비교 기능을 제공한다.

관련 요구사항: CHG-04·07  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | Y | 제안 |
| `comparisons` | [Comparison[]](00-common.md#schema-comparison) | Y | 기준·현재·수정안 비교 |
| `item_texts` | `object[]` | N | 항목별 {design_item_id, before_text, after_text}. 바뀌는 내용 탭의 −/+ 표시. 수정안 요청 |

```json
{
  "proposal": {
    "id": "71",
    "project_id": "101",
    "base_baseline_id": "401",
    "title": "로그인 오류 응답 설명 보완",
    "reason": "오류 표시 기준을 명확하게 한다.",
    "task_id": "501",
    "validation_id": "601",
    "items": [
      {
        "operation": "UPDATE",
        "design_item_id": "301",
        "base_design_item_version_id": "311",
        "schema_version": 1,
        "proposed_content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "수정된 로그인 계약"
        }
      }
    ],
    "documents": [
      {
        "document_id": "201",
        "base_document_version_id": "211",
        "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
      }
    ],
    "status": "PROPOSED",
    "proposer_account_id": "11",
    "actor_type": "HUMAN",
    "current_baseline_id": "401",
    "requires_refresh": false,
    "created_at": "2026-10-23T03:00:00Z",
    "updated_at": "2026-10-23T03:00:00Z"
  },
  "comparisons": [
    {
      "target_type": "DOCUMENT",
      "target_id": "201",
      "format": "TEXT",
      "base_content": "이전 설명",
      "current_content": "현재 설명",
      "proposed_content": "수정 설명",
      "diff": "- 이전 설명\n+ 수정 설명"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 기준·현재·제안 내용을 각각 구분해 표시한다.
- 바이너리는 텍스트 본문 대신 버전 ID·해시로 표시하며 실제 파일 다운로드로 확인한다.
- (수정안 요청) 바뀌는 내용 탭은 항목마다 한 줄로 접고, 펼치면 기준 문장과 제안 문장을 −/+로 보인다. 문장은 item_texts에서 읽는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a04"></a>

## CHG-A04. 설계 변경 제안 갱신·재제출

### PUT `/api/projects/{id}/change-proposals/{proposal_id}`

설계 변경 제안 갱신·재제출 기능을 제공한다.

관련 요구사항: APR-05  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER (제안자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `expected_updated_at` | `Time` | Y | 조회한 제안 updated_at. 낙관적 충돌 검사 |

```json
{
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "expected_updated_at": "2026-10-23T03:00:00Z"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED` | Y | 제안 상태 |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |

```json
{
  "id": "71",
  "project_id": "101",
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "status": "PROPOSED",
  "proposer_account_id": "11",
  "actor_type": "HUMAN",
  "current_baseline_id": "401",
  "requires_refresh": false,
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_conflict` | 갱신 기준이 현재가 아님 |
| 409 | `proposal_changed` | 동시 수정 |
| 409 | `invalid_transition` | 승인·발행·반려 완료 제안 |

```json
{
  "error": {
    "code": "baseline_conflict",
    "message": "갱신 기준이 현재가 아님"
  }
}
```

#### 처리 규칙

- PROPOSED/IN_REVIEW 상태만 갱신하며 결과 상태는 PROPOSED다.
- 현재 원문을 읽고 수정한 최종본을 제출한다. 영향·관련자 정보를 다시 확인한다.
- 이전 제출·검토 내용은 이력으로 보존한다. 제안 내용 변경을 승인 없이 발행하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a05"></a>

## CHG-A05. 변경 영향 조회

### GET `/api/projects/{id}/change-proposals/{proposal_id}/impact`

변경 영향 조회 기능을 제공한다.

관련 요구사항: CHG-05·08, BEE-06·09  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `explicit` | [ExplicitImpact[]](00-common.md#schema-explicitimpact) | Y | 명시 관계 영향 |
| `candidates` | [ImpactCandidate[]](00-common.md#schema-impactcandidate) | Y | AI 영향 후보 |
| `analysis_status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | AI 분석 상태 |
| `generation` | [Generation](00-common.md#schema-generation) | N | 분석 설명 |
| `error_code` | `string` | N | 실패 원인 |

```json
{
  "explicit": [
    {
      "task_id": "501",
      "member_id": "21",
      "design_item_id": "301",
      "validation_id": "601"
    }
  ],
  "candidates": [
    {
      "task_id": "502",
      "member_id": "22",
      "design_item_id": "302",
      "reason": "로그인 응답을 사용하는 화면입니다.",
      "sources": [
        {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      ]
    }
  ],
  "analysis_status": "COMPLETED",
  "generation": {
    "origin": "MODEL",
    "model": "configured-model",
    "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "usage": [
      {
        "request_key": "llm-validate-601-attempt-1",
        "feature": "TASK_VALIDATION",
        "model": "configured-model",
        "input_tokens": 1200,
        "output_tokens": 300,
        "latency_ms": 1800,
        "failed": false,
        "estimated_cost_amount": "0.0042",
        "currency": "USD",
        "tokens_known": true
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 명시 관계 영향과 AI 후보·분석 실패를 별도로 반환한다.
- AI 결과를 영향 확정이나 승인으로 취급하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a06"></a>

## CHG-A06. 변경 영향 AI 재분석

### POST `/api/projects/{id}/change-proposals/{proposal_id}/impact/retry`

변경 영향 AI 재분석 기능을 제공한다.

관련 요구사항: CHG-08  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `explicit` | [ExplicitImpact[]](00-common.md#schema-explicitimpact) | Y | 명시 관계 영향 |
| `candidates` | [ImpactCandidate[]](00-common.md#schema-impactcandidate) | Y | AI 영향 후보 |
| `analysis_status` | `PENDING\|RUNNING\|COMPLETED\|FAILED` | Y | AI 분석 상태 |
| `generation` | [Generation](00-common.md#schema-generation) | N | 분석 설명 |
| `error_code` | `string` | N | 실패 원인 |

```json
{
  "explicit": [
    {
      "task_id": "501",
      "member_id": "21",
      "design_item_id": "301",
      "validation_id": "601"
    }
  ],
  "candidates": [
    {
      "task_id": "502",
      "member_id": "22",
      "design_item_id": "302",
      "reason": "로그인 응답을 사용하는 화면입니다.",
      "sources": [
        {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      ]
    }
  ],
  "analysis_status": "RUNNING",
  "generation": {
    "origin": "MODEL",
    "model": "configured-model",
    "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "usage": [
      {
        "request_key": "llm-validate-601-attempt-1",
        "feature": "TASK_VALIDATION",
        "model": "configured-model",
        "input_tokens": 1200,
        "output_tokens": 300,
        "latency_ms": 1800,
        "failed": false,
        "estimated_cost_amount": "0.0042",
        "currency": "USD",
        "tokens_known": true
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `analysis_running` | 이미 분석 중 |

```json
{
  "error": {
    "code": "analysis_running",
    "message": "이미 분석 중"
  }
}
```

#### 처리 규칙

- 현재 제안·관련 작업 스냅샷으로 재분석한다. 원본 제안을 수정하지 않는다.
- 명시 관계 수신자 알림은 AI 실패와 관계없이 유지한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a07"></a>

## CHG-A07. 문서 수정 초안 생성

### POST `/api/projects/{id}/change-drafts`

문서 수정 초안 생성 기능을 제공한다.

관련 요구사항: BEE-08  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 대상 문서 |
| `base_document_version_id` | `ID` | Y | 기준 원문 |
| `instruction` | `string` | Y | 수정 요청 |
| `task_id` | `ID` | N | 관련 작업 |

```json
{
  "document_id": "201",
  "base_document_version_id": "211",
  "instruction": "오류 응답 필드 설명을 보완해 주세요.",
  "task_id": "501"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `document_id` | `ID` | Y | 문서 |
| `base_document_version_id` | `ID` | Y | 생성 기준 |
| `after_content` | `string` | Y | 미승인 수정안 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | 생성 출처·근거 |

```json
{
  "document_id": "201",
  "base_document_version_id": "211",
  "after_content": "# 로그인\n오류 응답은 error.code와 error.message를 반환한다.",
  "generation": {
    "origin": "MODEL",
    "model": "configured-model",
    "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "usage": [
      {
        "request_key": "llm-validate-601-attempt-1",
        "feature": "TASK_VALIDATION",
        "model": "configured-model",
        "input_tokens": 1200,
        "output_tokens": 300,
        "latency_ms": 1800,
        "failed": false,
        "estimated_cost_amount": "0.0042",
        "currency": "USD",
        "tokens_known": true
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 기준 변경 |
| 502 | `ai_unavailable` | 초안 생성 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "기준 변경"
  }
}
```

#### 처리 규칙

- 초안 생성으로 원문·승인본을 직접 바꾸지 않는다.
- 생성된 내용을 사람이 확인하고 CHG-A01로 변경 제안을 제출한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a08"></a>

## CHG-A08. 변경 제안 검토 시작

### POST `/api/projects/{id}/change-proposals/{proposal_id}/review`

변경 제안 검토 시작 기능을 제공한다.

관련 요구사항: CHG-03, APR-02  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_updated_at` | `Time` | Y | 조회한 제안 updated_at. 낙관적 충돌 검사 |

```json
{
  "expected_updated_at": "2026-10-23T03:00:00Z"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED` | Y | 제안 상태 |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |

```json
{
  "id": "71",
  "project_id": "101",
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "status": "IN_REVIEW",
  "proposer_account_id": "11",
  "actor_type": "HUMAN",
  "current_baseline_id": "401",
  "requires_refresh": false,
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `proposal_changed` | 제안 변경 |
| 409 | `invalid_transition` | 검토 시작 불가 상태 |

```json
{
  "error": {
    "code": "proposal_changed",
    "message": "제안 변경"
  }
}
```

#### 처리 규칙

- 현재 LEADER가 검토 상태만 변경한다. 승인·발행은 별도 동작이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a09"></a>

## CHG-A09. 변경 제안 승인·발행

### POST `/api/projects/{id}/change-proposals/{proposal_id}/approve`

변경 제안 승인·발행 기능을 제공한다.

관련 요구사항: APR-01·03~05  
담당: BE: 오서현 / 검증 영향: 변준성 / AI: — / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_base_baseline_id` | `ID` | Y | 검토한 제안 기준 |
| `expected_updated_at` | `Time` | Y | 조회한 제안 updated_at. 낙관적 충돌 검사 |

```json
{
  "expected_base_baseline_id": "401",
  "expected_updated_at": "2026-10-23T03:00:00Z"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | Y | PUBLISHED 제안 |
| `baseline` | [Baseline](00-common.md#schema-baseline) | Y | 새 발행 기준 |

```json
{
  "proposal": {
    "id": "71",
    "project_id": "101",
    "base_baseline_id": "401",
    "title": "로그인 오류 응답 설명 보완",
    "reason": "오류 표시 기준을 명확하게 한다.",
    "task_id": "501",
    "validation_id": "601",
    "items": [
      {
        "operation": "UPDATE",
        "design_item_id": "301",
        "base_design_item_version_id": "311",
        "schema_version": 1,
        "proposed_content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "수정된 로그인 계약"
        }
      }
    ],
    "documents": [
      {
        "document_id": "201",
        "base_document_version_id": "211",
        "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
      }
    ],
    "status": "PUBLISHED",
    "proposer_account_id": "11",
    "actor_type": "HUMAN",
    "current_baseline_id": "402",
    "requires_refresh": false,
    "created_at": "2026-10-23T03:00:00Z",
    "updated_at": "2026-10-23T03:00:00Z",
    "published_baseline_id": "402"
  },
  "baseline": {
    "id": "402",
    "project_id": "101",
    "version_no": 2,
    "documents": [
      {
        "document_id": "201",
        "document_version_id": "212"
      }
    ],
    "items": [
      {
        "design_item_id": "301",
        "design_item_version_id": "312"
      }
    ],
    "approved_by": "11",
    "created_at": "2026-10-23T03:00:00Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_conflict` | 제안 기준과 현재 baseline 불일치 |
| 409 | `proposal_changed` | 검토 뒤 제안 변경 |
| 409 | `invalid_transition` | 반려 등 승인 불가 |
| 500 | `internal` | 발행 트랜잭션 실패 |

```json
{
  "error": {
    "code": "baseline_conflict",
    "message": "제안 기준과 현재 baseline 불일치"
  }
}
```

#### 처리 규칙

- 사람 세션의 현재 LEADER만 허용하며 프로젝트 잠금 후 제안·현재 기준을 재검사한다.
- 문서·항목 버전, baseline과 두 스냅샷, 승인 이력, PUBLISHED, 현재 포인터를 원자적으로 저장한다.
- 같은 트랜잭션에서 변준성 서비스가 관련 검증 STALE·진행 작업 WAITING(REVALIDATION)을 반영한다. 하나라도 실패하면 전체 롤백한다.
- 관련 검증 = 변경 항목의 이전 버전을 design_refs(READS·IMPLEMENTS·CHANGES)에 가진 PASS·RUNNING 검증이다. stale_reason을 남긴다. RUNNING 검증의 늦은 결과는 반영되지 않는다. 시작 전 작업은 상태를 유지하고 IN_PROGRESS 작업만 WAITING(REVALIDATION)으로 바꾼다.
- 커밋 뒤 알림·활동을 처리한다. 커밋 뒤 전달 실패로 새 기준을 중복 발행하지 않는다.
- 이미 발행한 제안의 재요청은 기존 결과를 반환한다. D7 구현 경계는 Q03이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a10"></a>

## CHG-A10. 변경 제안 반려

### POST `/api/projects/{id}/change-proposals/{proposal_id}/reject`

변경 제안 반려 기능을 제공한다.

관련 요구사항: APR-01·02  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `reason` | `string` | Y | 반려·보완 사유 |
| `expected_updated_at` | `Time` | Y | 조회한 제안 updated_at. 낙관적 충돌 검사 |

```json
{
  "reason": "현재 API 계약과의 차이를 명확하게 작성해 주세요.",
  "expected_updated_at": "2026-10-23T03:00:00Z"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 제안 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `base_baseline_id` | `ID` | Y | 제안 기준 |
| `title` | `string` | Y | 제안 제목 |
| `reason` | `string` | Y | 변경 이유 |
| `task_id` | `ID` | N | 관련 작업 |
| `validation_id` | `ID` | N | 관련 검증 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 항목 변경 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 원문 변경 |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED` | Y | 제안 상태 |
| `proposer_account_id` | `ID` | Y | 제안 계정 |
| `actor_type` | `HUMAN\|AGENT` | Y | 제출 주체 |
| `current_baseline_id` | `ID` | Y | 현재 기준 |
| `requires_refresh` | `boolean` | Y | 기준 갱신 필요 |
| `published_baseline_id` | `ID` | N | 발행된 기준 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 최종 갱신 |
| `rejection_reason` | `string` | N | REJECTED인 경우의 사유 |

```json
{
  "id": "71",
  "project_id": "101",
  "base_baseline_id": "401",
  "title": "로그인 오류 응답 설명 보완",
  "reason": "오류 표시 기준을 명확하게 한다.",
  "task_id": "501",
  "validation_id": "601",
  "items": [
    {
      "operation": "UPDATE",
      "design_item_id": "301",
      "base_design_item_version_id": "311",
      "schema_version": 1,
      "proposed_content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "수정된 로그인 계약"
      }
    }
  ],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
    }
  ],
  "status": "REJECTED",
  "proposer_account_id": "11",
  "actor_type": "HUMAN",
  "current_baseline_id": "401",
  "requires_refresh": false,
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z",
  "rejection_reason": "현재 API 계약과의 차이를 명확하게 작성해 주세요."
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `proposal_changed` | 경합 |
| 409 | `invalid_transition` | 이미 처리 완료 |

```json
{
  "error": {
    "code": "proposal_changed",
    "message": "경합"
  }
}
```

#### 처리 규칙

- 반려 사유와 검토 이력을 보존한다.
- 반려 후 재제출은 새 제안 생성 계약으로 두며 기존 제안을 덮어쓰지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a11"></a>

## CHG-A11. 승인 이력 조회

### GET `/api/projects/{id}/change-proposals/{proposal_id}/approval`

승인 이력 조회 기능을 제공한다.

관련 요구사항: APR-04, DOC-09  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 승인 이력 |
| `proposal_id` | `ID` | Y | 제안 |
| `approved_by` | `ID` | Y | 승인 계정 |
| `approved_at` | `Time` | Y | 승인 시각 |
| `baseline_id` | `ID` | Y | 발행 기준 |

```json
{
  "id": "81",
  "proposal_id": "71",
  "approved_by": "11",
  "approved_at": "2026-10-23T03:00:00Z",
  "baseline_id": "402"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "not_found",
    "message": "승인 이력 없음"
  }
}
```

#### 처리 규칙

- 실제로 발행된 제안에 연결된 승인자·시각·baseline을 조회한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a12"></a>

## CHG-A12. 변경 제안 의견·검토 요청 등록

### POST `/api/projects/{id}/change-proposals/{proposal_id}/comments`

변경 제안 의견·검토 요청 등록 기능을 제공한다.

관련 요구사항: APR-02, MTG-04  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `body` | `string` | Y | 의견 내용 |
| `kind` | `COMMENT\|REVIEW_REQUEST` | N | 의견 종류. 기본 COMMENT |
| `mentioned_member_ids` | `ID[]` | N | 관련 멤버. 무관한 전체 자동 호출 금지 |

```json
{
  "body": "프론트의 응답 처리와 함께 검토해 주세요.",
  "kind": "COMMENT",
  "mentioned_member_ids": [
    "21"
  ]
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 의견 ID |
| `proposal_id` | `ID` | Y | 제안 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `kind` | `COMMENT\|REVIEW_REQUEST` | Y | 의견 종류 |
| `body` | `string` | Y | 의견 본문 |
| `created_at` | `Time` | Y | 작성 시각 |

```json
{
  "id": "91",
  "proposal_id": "71",
  "author_account_id": "11",
  "kind": "COMMENT",
  "body": "프론트도 이 필드를 사용하겠습니다.",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 프로젝트 멤버만 의견을 남긴다. 의견 작성은 승인과 다르다.
- 관련자 알림의 대상·내용은 문서 도메인이 결정해 전진 서비스로 전달한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a13"></a>

## CHG-A13. 변경 제안 의견 목록

### GET `/api/projects/{id}/change-proposals/{proposal_id}/comments`

변경 제안 의견 목록 기능을 제공한다.

관련 요구사항: APR-02, MTG-04  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ChangeComment[]](00-common.md#schema-changecomment) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "91",
      "proposal_id": "71",
      "author_account_id": "11",
      "kind": "COMMENT",
      "body": "프론트도 이 필드를 사용하겠습니다.",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a14"></a>

## CHG-A14. 승인 미리 보기

### GET `/api/projects/{id}/change-proposals/{proposal_id}/approval-preview`

승인하면 같이 바뀌는 기준 항목·검증·작업을 저장 없이 미리 계산한다.

관련 요구사항: CHG-05 (화면 PNL-04-v2-approve)  
담당: BE: 오서현 / 검증 영향: 변준성 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `next_baseline_version_no` | `integer` | Y | 발행될 기준 번호 |
| `baseline_items` | `object[]` | Y | 새 기준에 들어갈 항목 {design_item_id, title, version_no} |
| `validations_to_stale` | `object[]` | Y | '다시 검증 필요'가 될 검증 {validation_id, task_id, task_title} |
| `tasks_to_wait` | `object[]` | Y | '대기 · 재검증'이 될 진행 중 작업 {task_id, title, assignee_member_id} |
| `unchanged` | `object` | Y | 그대로인 것 {items, documents}. 화면 '나머지 항목 1개와 문서 4개는 v1 그대로' |
| `unreviewed` | `object` | Y | 확인하지 않은 것 {ai_candidates, open_review_requests}. 화면 'AI 영향 후보 1개 · 답하지 않은 검토 요청 1개' |

```json
{
  "next_baseline_version_no": 2,
  "baseline_items": [
    {
      "design_item_id": "701",
      "title": "REQ-001 프로젝트 목록 빈 화면 안내",
      "version_no": 2
    }
  ],
  "validations_to_stale": [
    {
      "validation_id": "601",
      "task_id": "502",
      "task_title": "TASK-002"
    }
  ],
  "tasks_to_wait": [
    {
      "task_id": "503",
      "title": "TASK-003",
      "assignee_member_id": "23"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `baseline_conflict` | 제안 기준이 현재와 다름(requires_refresh) |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- CHG-A09와 같은 규칙으로 계산하되 아무것도 저장하지 않는다.
- 승인 확인 화면에서만 쓴다. 그사이 기준이나 제안이 바뀌면 CHG-A09가 기존 규칙대로 거절하고 화면은 다시 확인하라고 안내한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a15"></a>

## CHG-A15. 변경 제안 거두기

### POST `/api/projects/{id}/change-proposals/{proposal_id}/withdraw`

제안자가 스스로 제안을 끝낸다.

관련 요구사항: CHG-07 (화면 PNL-04-v2-changes)  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (제안자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_updated_at` | `Time` | Y | 조회한 제안 updated_at |

```json
{
  "expected_updated_at": "2026-10-28T03:00:00Z"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | Y | status=WITHDRAWN인 제안 |

```json
{
  "proposal": {
    "id": "71",
    "status": "WITHDRAWN",
    "updated_at": "2026-10-28T03:01:00Z"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `invalid_transition` | 이미 승인·발행·반려·거둠 |
| 409 | `version_conflict` | expected_updated_at이 현재와 다름 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- PROPOSED·IN_REVIEW 제안만 거둔다. 거둔 제안은 고칠 수 없고 다시 올리면 새 제안이다(previous_proposal_id).
- 검토 요청을 받은 팀원에게 알린다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a01"></a>

## TSK-A01. 작업 계획 등록

### POST `/api/projects/{id}/tasks`

작업 계획 등록 기능을 제공한다.

관련 요구사항: TSK-01, MCP-10  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `title` | `string` | Y | 작업 제목 |
| `goal` | `string` | Y | 작업 목표 |
| `included_scope` | `string[]` | Y | 포함 범위 |
| `excluded_scope` | `string[]` | Y | 제외 범위 |
| `approach` | `string` | Y | 접근 방식 |
| `completion_criteria` | `string[]` | Y | 완료 기준 |
| `dependency_task_ids` | `ID[]` | Y | 선행 작업 |
| `design_refs` | [DesignRef[]](00-common.md#schema-designref) | Y | 확정 참조 |
| `unlinked` | `object[]` | Y | 미연결 내용. 세부 항목 Q02. 수정안 요청: 줄마다 문장 하나라 `{text}` 배열로 확정 |
| `no_contract_change` | `boolean` | Y | 계약 변경 없음의 명시적 선언 |
| `context_packet_id` | `ID` | N | 근거 패킷. 없으면 근거 패킷 없음 표시 |

```json
{
  "title": "로그인 화면 구현",
  "goal": "승인 계약대로 로그인 화면을 연결한다.",
  "included_scope": [
    "로그인 폼과 오류 표시"
  ],
  "excluded_scope": [
    "회원가입"
  ],
  "approach": "승인 API 계약을 사용한다.",
  "completion_criteria": [
    "로그인 성공 시 프로젝트 목록으로 이동"
  ],
  "dependency_task_ids": [],
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "unlinked": [],
  "no_contract_change": true,
  "context_packet_id": "701"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "READY",
  "lock_version": 1,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "NOT_RUN",
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `dependency_cycle` | 선행 순환 |
| 409 | `baseline_required` | 기준 v1 발행 전 |

```json
{
  "error": {
    "code": "dependency_cycle",
    "message": "선행 순환"
  }
}
```

#### 처리 규칙

- 호출자의 멤버 ID를 담당자로 초기화한다. ID·표시 키는 서버 발급이다.
- 첫 불변 계획 리비전과 설계 참조를 저장하고 READY로 둔다. READY 자체는 시작 승인 상태가 아니다.
- 의존 작업은 같은 프로젝트여야 하며 자기 자신·순환은 거부한다.
- (수정안 요청) 기준 v1 발행 전에는 409 baseline_required다. 팀원 시작 가이드와 설계 관계 빈 화면이 이 규칙을 전제로 한다(Q22).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a02"></a>

## TSK-A02. 작업 목록

### GET `/api/projects/{id}/tasks`

작업 목록 기능을 제공한다.

관련 요구사항: MON-03·04  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | N | 작업 상태 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | N | 검증 상태 |
| `assignee_member_id` | `ID` | N | 담당 멤버 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [TaskDetail[]](00-common.md#schema-taskdetail) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "501",
      "key": "TASK-001",
      "project_id": "101",
      "assignee_member_id": "21",
      "status": "READY",
      "lock_version": 1,
      "current_plan_revision": {
        "id": "511",
        "task_id": "501",
        "revision_no": 1,
        "plan": {
          "title": "로그인 화면 구현",
          "goal": "승인 계약대로 로그인 화면을 연결한다.",
          "included_scope": [
            "로그인 폼과 오류 표시"
          ],
          "excluded_scope": [
            "회원가입"
          ],
          "approach": "승인 API 계약을 사용한다.",
          "completion_criteria": [
            "로그인 성공 시 프로젝트 목록으로 이동"
          ],
          "dependency_task_ids": [],
          "design_refs": [
            {
              "design_item_id": "301",
              "design_item_version_id": "311",
              "relation": "IMPLEMENTS"
            }
          ],
          "unlinked": [],
          "no_contract_change": true,
          "context_packet_id": "701"
        },
        "created_at": "2026-10-23T03:00:00Z",
        "created_by": "11"
      },
      "validation_status": "PASS",
      "validation": {
        "id": "601",
        "task_id": "501",
        "plan_revision_id": "511",
        "baseline_id": "401",
        "status": "PASS",
        "current_attempt": 1,
        "design_refs": [
          {
            "design_item_id": "301",
            "design_item_version_id": "311",
            "relation": "IMPLEMENTS"
          }
        ],
        "related_task_refs": [
          {
            "task_id": "502",
            "plan_revision_id": "512"
          }
        ],
        "findings": [],
        "cache_hit": false,
        "created_at": "2026-10-23T03:00:00Z",
        "finished_at": "2026-10-23T03:00:00Z"
      },
      "created_at": "2026-10-23T03:00:00Z",
      "updated_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a03"></a>

## TSK-A03. 작업 상세

### GET `/api/projects/{id}/tasks/{task_id}`

작업 상세 기능을 제공한다.

관련 요구사항: TSK-01~03  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "READY",
  "lock_version": 1,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "PASS",
  "validation": {
    "id": "601",
    "task_id": "501",
    "plan_revision_id": "511",
    "baseline_id": "401",
    "status": "PASS",
    "current_attempt": 1,
    "design_refs": [
      {
        "design_item_id": "301",
        "design_item_version_id": "311",
        "relation": "IMPLEMENTS"
      }
    ],
    "related_task_refs": [
      {
        "task_id": "502",
        "plan_revision_id": "512"
      }
    ],
    "findings": [],
    "cache_hit": false,
    "created_at": "2026-10-23T03:00:00Z",
    "finished_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 계획에 해당하는 검증을 계산해 반환한다. 현재 포인터 중복 저장을 요구하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a04"></a>

## TSK-A04. 작업 계획 새 리비전 저장

### POST `/api/projects/{id}/tasks/{task_id}/plan-revisions`

작업 계획 새 리비전 저장 기능을 제공한다.

관련 요구사항: TSK-01, MCP-10  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER + TASK_OWNER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `title` | `string` | Y | 작업 제목 |
| `goal` | `string` | Y | 작업 목표 |
| `included_scope` | `string[]` | Y | 포함 범위 |
| `excluded_scope` | `string[]` | Y | 제외 범위 |
| `approach` | `string` | Y | 접근 방식 |
| `completion_criteria` | `string[]` | Y | 완료 기준 |
| `dependency_task_ids` | `ID[]` | Y | 선행 작업 |
| `design_refs` | [DesignRef[]](00-common.md#schema-designref) | Y | 확정 참조 |
| `unlinked` | `object[]` | Y | 미연결 내용. 세부 항목 Q02 |
| `no_contract_change` | `boolean` | Y | 계약 변경 없음의 명시적 선언 |
| `context_packet_id` | `ID` | N | 근거 패킷. 없으면 근거 패킷 없음 표시 |
| `expected_revision_no` | `integer` | Y | 현재 계획 번호 |
| `expected_lock_version` | `integer` | Y | 현재 작업 잠금 번호 |

```json
{
  "title": "로그인 화면 구현",
  "goal": "승인 계약대로 로그인 화면을 연결한다.",
  "included_scope": [
    "로그인 폼과 오류 표시"
  ],
  "excluded_scope": [
    "회원가입"
  ],
  "approach": "승인 API 계약을 사용한다.",
  "completion_criteria": [
    "로그인 성공 시 프로젝트 목록으로 이동"
  ],
  "dependency_task_ids": [],
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "unlinked": [],
  "no_contract_change": true,
  "context_packet_id": "701",
  "expected_revision_no": 1,
  "expected_lock_version": 1
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "READY",
  "lock_version": 2,
  "current_plan_revision": {
    "id": "513",
    "task_id": "501",
    "revision_no": 2,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "NOT_RUN",
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 계획 또는 작업 상태 변경 |
| 409 | `dependency_cycle` | 의존 순환 |

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "계획 또는 작업 상태 변경"
  }
}
```

#### 처리 규칙

- 기존 계획을 덮어쓰지 않고 새 불변 리비전을 생성한다.
- 진행 중 계획 변경은 기존 검증을 STALE로 하고 WAITING(REVALIDATION)으로 전환하며 점유는 유지한다.
- 입력 변경에 따른 무효화 세부는 담당자가 검토한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a05"></a>

## TSK-A05. 작업 계획 리비전 이력

### GET `/api/projects/{id}/tasks/{task_id}/plan-revisions`

작업 계획 리비전 이력 기능을 제공한다.

관련 요구사항: TSK-01, TRC-03  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [TaskPlanRevision[]](00-common.md#schema-taskplanrevision) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "511",
      "task_id": "501",
      "revision_no": 1,
      "plan": {
        "title": "로그인 화면 구현",
        "goal": "승인 계약대로 로그인 화면을 연결한다.",
        "included_scope": [
          "로그인 폼과 오류 표시"
        ],
        "excluded_scope": [
          "회원가입"
        ],
        "approach": "승인 API 계약을 사용한다.",
        "completion_criteria": [
          "로그인 성공 시 프로젝트 목록으로 이동"
        ],
        "dependency_task_ids": [],
        "design_refs": [
          {
            "design_item_id": "301",
            "design_item_version_id": "311",
            "relation": "IMPLEMENTS"
          }
        ],
        "unlinked": [],
        "no_contract_change": true,
        "context_packet_id": "701"
      },
      "created_at": "2026-10-23T03:00:00Z",
      "created_by": "11"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 당시 참조 버전·근거 패킷을 그대로 반환한다. 최신 내용으로 과거 리비전을 치환하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a06"></a>

## TSK-A06. 작업 담당자 변경

### PATCH `/api/projects/{id}/tasks/{task_id}/assignee`

작업 담당자 변경 기능을 제공한다.

관련 요구사항: TSK-02  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `assignee_member_id` | `ID` | Y | 같은 프로젝트의 새 담당자 |
| `expected_lock_version` | `integer` | Y | 조회한 잠금 번호 |

```json
{
  "assignee_member_id": "22",
  "expected_lock_version": 1
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "22",
  "status": "READY",
  "lock_version": 2,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "PASS",
  "validation": {
    "id": "601",
    "task_id": "501",
    "plan_revision_id": "511",
    "baseline_id": "401",
    "status": "PASS",
    "current_attempt": 1,
    "design_refs": [
      {
        "design_item_id": "301",
        "design_item_version_id": "311",
        "relation": "IMPLEMENTS"
      }
    ],
    "related_task_refs": [
      {
        "task_id": "502",
        "plan_revision_id": "512"
      }
    ],
    "findings": [],
    "cache_hit": false,
    "created_at": "2026-10-23T03:00:00Z",
    "finished_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 상태 경합 |
| 409 | `task_in_progress` | 진행 중 인계 조건 미충족 |

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "상태 경합"
  }
}
```

#### 처리 규칙

- 현재 사람 팀장만 담당자를 바꾼다. 진행 중 점유·인계·재검증 정책은 Q02다.
- 이전 작업 판단·패킷·검증을 새 담당자가 조회할 수 있게 유지한다.
- (수정안 요청) 바뀐 담당자에게 TASK 알림을 보낸다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="val-a01"></a>

## VAL-A01. 작업 검증 접수

### POST `/api/projects/{id}/tasks/{task_id}/validations`

작업 검증 접수 기능을 제공한다.

관련 요구사항: VAL-01·02, MCP-11  
담당: BE: 변준성 / AI: 양지훈 / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER + TASK_OWNER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `plan_revision_id` | `ID` | Y | 현재 계획 리비전 |
| `expected_lock_version` | `integer` | Y | 현재 잠금 번호 |

```json
{
  "plan_revision_id": "511",
  "expected_lock_version": 1
}
```

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 설계 대조 재사용 |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "RUNNING",
  "current_attempt": 1,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z"
}
```

HTTP `200`: 규칙 검사만으로 최초 응답 전에 종료한 경우. 최종 status는 PASS/NEEDS_INPUT/NEEDS_REVIEW다.

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "PASS",
  "current_attempt": 1,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z",
  "finished_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 현재 계획·상태 변경 |
| 409 | `baseline_missing` | 발행된 기준 설계 없음 |

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "현재 계획·상태 변경"
  }
}
```

#### 처리 규칙

- 검증 입력·설계·관련 작업 참조 스냅샷을 고정한다.
- 의미 분석이 필요하면 backend 큐/워커로 접수하고 RUNNING을 반환한다. 조회는 VAL-A02다.
- 판정은 [공통 6절 판정표](00-common.md#검증시작)를 따른다. NEEDS_INPUT·NEEDS_REVIEW로 확정되는 경우는 요청 안에서 응답한다. AI는 finding을 추가만 하며 AI 실패는 ERROR다.
- 현재 기준 설계가 없으면 409 `baseline_missing`이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="val-a02"></a>

## VAL-A02. 검증 결과 조회

### GET `/api/projects/{id}/validations/{validation_id}`

검증 결과 조회 기능을 제공한다.

관련 요구사항: MON-04, VAL-05  
담당: BE: 변준성 / AI: 양지훈 / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `validation_id` | `ID` | Y | 검증 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 설계 대조 재사용 |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "PASS",
  "current_attempt": 1,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z",
  "finished_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회로 새 검증·모델 호출을 만들지 않는다.
- RUNNING이면 같은 ID로 다시 조회한다. STALE·ERROR·보완 결과를 PASS로 간주하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="val-a03"></a>

## VAL-A03. 작업 검증 이력

### GET `/api/projects/{id}/tasks/{task_id}/validations`

작업 검증 이력 기능을 제공한다.

관련 요구사항: VAL-05, TRC-03  
담당: BE: 변준성 / AI: 양지훈 / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Validation[]](00-common.md#schema-validation) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "601",
      "task_id": "501",
      "plan_revision_id": "511",
      "baseline_id": "401",
      "status": "PASS",
      "current_attempt": 1,
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "related_task_refs": [
        {
          "task_id": "502",
          "plan_revision_id": "512"
        }
      ],
      "findings": [],
      "cache_hit": false,
      "created_at": "2026-10-23T03:00:00Z",
      "finished_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="val-a04"></a>

## VAL-A04. 검증 실행 재시도

### POST `/api/projects/{id}/validations/{validation_id}/retry`

검증 실행 재시도 기능을 제공한다.

관련 요구사항: VAL-01, NFR-06  
담당: BE: 변준성 / AI: 양지훈 / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER + TASK_OWNER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `validation_id` | `ID` | Y | 검증 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_attempt` | `integer` | Y | 조회한 현재 시도 번호 |

```json
{
  "expected_attempt": 1
}
```

#### Response

HTTP `202`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 검증 ID |
| `task_id` | `ID` | Y | 작업 |
| `plan_revision_id` | `ID` | Y | 입력 리비전 |
| `baseline_id` | `ID` | Y | 검증 당시 기준. 이력용 |
| `status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 검증 상태 |
| `current_attempt` | `integer` | Y | 현재 시도 |
| `design_refs` | [ValidationDesignRef[]](00-common.md#schema-validationdesignref) | Y | 사용한 항목 버전 |
| `related_task_refs` | [RelatedTaskRef[]](00-common.md#schema-relatedtaskref) | Y | 대조한 다른 계획 |
| `findings` | [Finding[]](00-common.md#schema-finding) | Y | 발견 항목 |
| `cache_hit` | `boolean` | Y | 설계 대조 재사용 |
| `created_at` | `Time` | Y | 접수 시각 |
| `finished_at` | `Time` | N | 완료 시각 |
| `error_code` | `string` | N | ERROR일 때 필수, 그 외 생략. 현재 실패 attempt의 [검증 실행 오류 코드](00-common.md#validation-execution-errors) |

```json
{
  "id": "601",
  "task_id": "501",
  "plan_revision_id": "511",
  "baseline_id": "401",
  "status": "RUNNING",
  "current_attempt": 2,
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311",
      "relation": "IMPLEMENTS"
    }
  ],
  "related_task_refs": [
    {
      "task_id": "502",
      "plan_revision_id": "512"
    }
  ],
  "findings": [],
  "cache_hit": false,
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `invalid_transition` | ERROR가 아니거나 현재 실패 실행의 오류가 RETRY 대상이 아님, 또는 이미 재실행 중 |
| 409 | `revision_conflict` | 입력·참조 변경 |

```json
{
  "error": {
    "code": "invalid_transition",
    "message": "현재 실패 실행이 재시도 대상이 아니거나 이미 재실행 중입니다."
  }
}
```

#### 처리 규칙

- ERROR 검증 중 현재 실패 attempt의 ERROR finding이 RETRY인 경우에만 같은 validation의 새 validation_run을 생성한다. 과거 실행의 RETRY finding으로 허용하지 않는다.
- 현재 실행에 HUMAN_REVIEW 오류가 하나라도 있으면 409 invalid_transition이다. 근거 부족·미지원 내용은 보완한 뒤 새 검증을 요청한다. FE 버튼 표시와 별개로 서버도 같은 제한을 검사한다.
- STALE 또는 입력 변경은 새 검증을 생성해야 한다.
- lease/attempt가 맞는 RUNNING 결과만 반영한다. 늦은 결과를 폐기하더라도 실제 비용은 기록한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="tsk-a07"></a>

## TSK-A07. 작업 상태 보고

### POST `/api/projects/{id}/tasks/{task_id}/state-events`

작업 상태 보고 기능을 제공한다.

관련 요구사항: TSK-03·04, MCP-12  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER + TASK_OWNER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `task_id` | `ID` | Y | 작업 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `event` | `START\|RESUME\|WAIT\|COMPLETED\|CANCEL` | Y | 전이 이벤트 |
| `plan_revision_id` | `ID` | Y | 현재 계획 |
| `expected_lock_version` | `integer` | Y | 조회한 lock_version |
| `validation_id` | `ID` | N | START/RESUME 필수 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY` | N | WAIT이면 필수. WAIT는 IN_PROGRESS에서만 가능. REVALIDATION은 시스템 전용이라 지정하면 400 invalid_transition |
| `summary` | `string` | N | 진행/완료 요약 |
| `delta` | `object[]` | N | 완료 시 계획 대비 변경. 있으면 WAITING(REVALIDATION)으로 두고 delta 검증이 PASS하면 서버가 COMPLETED로 전이 |
| `completion_checks` | [CompletionCheck[]](00-common.md#schema-completioncheck) | N | 완료 기준 확인 |

```json
{
  "event": "START",
  "plan_revision_id": "511",
  "expected_lock_version": 1,
  "validation_id": "601"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 작업 ID |
| `key` | `string` | Y | 표시 키 |
| `project_id` | `ID` | Y | 프로젝트 |
| `assignee_member_id` | `ID` | Y | 담당 멤버 |
| `status` | `DRAFT\|READY\|IN_PROGRESS\|WAITING\|COMPLETED\|CANCELLED` | Y | 작업 상태 |
| `waiting_reason` | `INPUT\|APPROVAL\|DEPENDENCY\|REVALIDATION` | N | WAITING일 때만 포함 |
| `lock_version` | `integer` | Y | 상태 충돌 검사 번호 |
| `current_plan_revision` | [TaskPlanRevision](00-common.md#schema-taskplanrevision) | Y | 현재 계획 |
| `validation_status` | `NOT_RUN\|RUNNING\|PASS\|NEEDS_INPUT\|NEEDS_REVIEW\|STALE\|ERROR` | Y | 현재 계획 검증 |
| `validation` | [Validation](00-common.md#schema-validation) | N | 현재 계획 검증 결과 |
| `started_validation_id` | `ID` | N | 시작에 사용한 검증 |
| `created_at` | `Time` | Y | 생성 시각 |
| `updated_at` | `Time` | Y | 갱신 시각 |

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "IN_PROGRESS",
  "lock_version": 1,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "PASS",
  "validation": {
    "id": "601",
    "task_id": "501",
    "plan_revision_id": "511",
    "baseline_id": "401",
    "status": "PASS",
    "current_attempt": 1,
    "design_refs": [
      {
        "design_item_id": "301",
        "design_item_version_id": "311",
        "relation": "IMPLEMENTS"
      }
    ],
    "related_task_refs": [
      {
        "task_id": "502",
        "plan_revision_id": "512"
      }
    ],
    "findings": [],
    "cache_hit": false,
    "created_at": "2026-10-23T03:00:00Z",
    "finished_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z",
  "started_validation_id": "601"
}
```

HTTP `202`: COMPLETED 보고의 delta가 있어 검증을 접수한 경우. Q03 합의 대상.

```json
{
  "id": "501",
  "key": "TASK-001",
  "project_id": "101",
  "assignee_member_id": "21",
  "status": "WAITING",
  "lock_version": 1,
  "current_plan_revision": {
    "id": "511",
    "task_id": "501",
    "revision_no": 1,
    "plan": {
      "title": "로그인 화면 구현",
      "goal": "승인 계약대로 로그인 화면을 연결한다.",
      "included_scope": [
        "로그인 폼과 오류 표시"
      ],
      "excluded_scope": [
        "회원가입"
      ],
      "approach": "승인 API 계약을 사용한다.",
      "completion_criteria": [
        "로그인 성공 시 프로젝트 목록으로 이동"
      ],
      "dependency_task_ids": [],
      "design_refs": [
        {
          "design_item_id": "301",
          "design_item_version_id": "311",
          "relation": "IMPLEMENTS"
        }
      ],
      "unlinked": [],
      "no_contract_change": true,
      "context_packet_id": "701"
    },
    "created_at": "2026-10-23T03:00:00Z",
    "created_by": "11"
  },
  "validation_status": "PASS",
  "validation": {
    "id": "601",
    "task_id": "501",
    "plan_revision_id": "511",
    "baseline_id": "401",
    "status": "PASS",
    "current_attempt": 1,
    "design_refs": [
      {
        "design_item_id": "301",
        "design_item_version_id": "311",
        "relation": "IMPLEMENTS"
      }
    ],
    "related_task_refs": [
      {
        "task_id": "502",
        "plan_revision_id": "512"
      }
    ],
    "findings": [],
    "cache_hit": false,
    "created_at": "2026-10-23T03:00:00Z",
    "finished_at": "2026-10-23T03:00:00Z"
  },
  "created_at": "2026-10-23T03:00:00Z",
  "updated_at": "2026-10-23T03:00:00Z",
  "started_validation_id": "601",
  "waiting_reason": "REVALIDATION"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 설계 참조가 오래됨 |
| 409 | `revision_conflict` | 계획·상태 경합 |
| 409 | `validation_not_passed` | 유효 PASS 없음 |
| 409 | `dependency_not_completed` | 선행 미완료 |
| 409 | `task_conflict` | CHANGES 점유 충돌 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "설계 참조가 오래됨"
  }
}
```

#### 처리 규칙

- START/RESUME는 validation_id가 필수다. 먼저 같은 (task_id, validation_id)의 기존 START 결과가 있으면 그대로 반환하고, 그다음 expected_lock_version을 검사한다.
- 프로젝트 행을 잠근 뒤 현재 계획 PASS, 참조 최신성(검증 design_refs의 버전 = 현재 baseline_item 버전), 선행 완료, 같은 항목 CHANGES 점유를 검사하고 전이한다. 전역 baseline 번호는 비교하지 않는다.
- WAIT는 IN_PROGRESS에서만 가능하며 사유 필수다. REVALIDATION은 시스템 전용이라 지정하면 400 `invalid_transition`이다.
- 사람 세션의 LEADER는 보고가 끊긴 작업을 CANCEL할 수 있다(AC-14). 그 밖의 전이는 담당자만 한다. CANCEL은 큐 취소·점유 해제·늦은 결과 차단을 수행한다.
- COMPLETED는 불변 완료 보고를 저장한다. delta가 있으면 WAITING(REVALIDATION)으로 두고 trigger=DELTA 검증을 접수하며 응답에 delta_validation_id를 포함한다. delta 검증이 PASS하면 서버가 COMPLETED로 전이한다.
- 같은 START(task_id, validation_id)는 기존 결과를 반환한다. 검증의 PASS는 실제 코드 품질 검증을 뜻하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a01"></a>

## CTX-A01. 개발 컨텍스트 패킷 조회

### GET `/api/projects/{id}/context-packets/{packet_id}`

개발 컨텍스트 패킷 조회 기능을 제공한다.

관련 요구사항: TRC-03, MCP-06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `packet_id` | `ID` | Y | 컨텍스트 패킷 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 패킷 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `task_id` | `ID` | N | 연결 작업 |
| `baseline_id` | `ID` | Y | 조회 당시 기준 |
| `request` | `string` | Y | 개발 요청 |
| `design_items` | [DesignItemVersion[]](00-common.md#schema-designitemversion) | Y | 승인 설계 |
| `constraints` | `string[]` | Y | 제약 |
| `unresolved` | `string[]` | Y | 미확정 항목 |
| `related_tasks` | [RelatedTask[]](00-common.md#schema-relatedtask) | Y | 관련 작업 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `created_at` | `Time` | Y | 생성 시각 |
| `content_hash` | `string` | Y | 패킷 해시 |

```json
{
  "id": "701",
  "project_id": "101",
  "task_id": "501",
  "baseline_id": "401",
  "request": "로그인 화면을 구현해 주세요.",
  "design_items": [
    {
      "id": "311",
      "design_item_id": "301",
      "key": "API-001",
      "kind": "API_CONTRACT",
      "version_no": 1,
      "status": "APPROVED",
      "schema_version": 1,
      "content": {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "이메일·비밀번호 로그인"
      },
      "source": {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    }
  ],
  "constraints": [
    "승인 계약을 변경하지 않는다."
  ],
  "unresolved": [],
  "related_tasks": [
    {
      "task_id": "502",
      "plan_revision_id": "512",
      "status": "IN_PROGRESS"
    }
  ],
  "sources": [
    {
      "document_id": "201",
      "document_version_id": "211",
      "version_no": 1,
      "location": "3. 계정 관리 / ACC-02"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z",
  "content_hash": "sha256:example-packet-hash"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- MCP 호출에서 생성한 불변 패킷을 조회한다. 웹에서 임의 패킷을 생성하지 않는다.
- 당시 승인본과 현재성이 다른 경우도 당시 근거를 보존한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a02"></a>

## CTX-A02. 공유 작업 기록 검색

### GET `/api/projects/{id}/context-records`

공유 작업 기록 검색 기능을 제공한다.

관련 요구사항: TRC-05, MCP-05  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `q` | `string` | N | 검색어 |
| `task_id` | `ID` | N | 작업 필터 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | N | 기록 종류 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ContextRecordSummary[]](00-common.md#schema-contextrecordsummary) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "801",
      "root_id": "801",
      "revision_no": 1,
      "task_id": "501",
      "kind": "HANDOVER",
      "summary": "로그인 UI 연결 완료",
      "author_account_id": "11",
      "visibility": "SHARED",
      "is_latest_revision": true,
      "references_current": true,
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- SHARED는 프로젝트 멤버에게, PRIVATE는 작성자에게만 보인다. 요약·개수에도 같은 권한을 적용한다.
- 최신 기록 여부와 참조 설계 현재성을 따로 표시한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a03"></a>

## CTX-A03. 작업 기록 상세

### GET `/api/projects/{id}/context-records/{record_id}`

작업 기록 상세 기능을 제공한다.

관련 요구사항: TRC-05, MCP-06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER / RECORD_AUTHOR`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `record_id` | `ID` | Y | 기록 리비전 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 리비전 ID |
| `root_id` | `ID` | Y | 최초 리비전 ID |
| `revision_no` | `integer` | Y | 리비전 번호 |
| `task_id` | `ID` | N | 관련 작업 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 종류 |
| `summary` | `string` | Y | 공유 요약 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `is_latest_revision` | `boolean` | Y | 최신 기록 리비전 |
| `references_current` | `boolean` | Y | 참조 설계의 현재성 |
| `created_at` | `Time` | Y | 작성 시각 |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

```json
{
  "id": "801",
  "root_id": "801",
  "revision_no": 1,
  "task_id": "501",
  "kind": "HANDOVER",
  "summary": "로그인 UI 연결 완료",
  "author_account_id": "11",
  "visibility": "SHARED",
  "is_latest_revision": true,
  "references_current": true,
  "created_at": "2026-10-23T03:00:00Z",
  "plan_revision_id": "511",
  "source_packet_id": "701",
  "detail": "오류 응답은 승인된 message 필드로 표시합니다.",
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- SHARED는 활성 멤버, PRIVATE는 활성 멤버 중 작성자만 허용한다.
- 개인 대화 원문 자동 수집이 아니라 명시적으로 제출한 기록만 반환한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a04"></a>

## CTX-A04. 공유 작업 기록 생성·리비전 추가

### POST `/api/projects/{id}/context-records`

공유 작업 기록 생성·리비전 추가 기능을 제공한다.

관련 요구사항: TRC-05, MCP-07  
담당: BE: 변준성(저장·조회), 전진(MCP 계약) / AI: 박상은(검색·맥락) / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `task_id` | `ID` | N | 연결 작업 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 기록 종류 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `summary` | `string` | Y | 검색용 공유 요약 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

```json
{
  "task_id": "501",
  "plan_revision_id": "511",
  "source_packet_id": "701",
  "kind": "HANDOVER",
  "visibility": "SHARED",
  "summary": "로그인 UI 연결 완료",
  "detail": "오류 응답은 승인된 message 필드로 표시합니다.",
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ]
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 리비전 ID |
| `root_id` | `ID` | Y | 최초 리비전 ID |
| `revision_no` | `integer` | Y | 리비전 번호 |
| `task_id` | `ID` | N | 관련 작업 |
| `kind` | `DECISION\|PROGRESS\|ISSUE\|HANDOVER` | Y | 종류 |
| `summary` | `string` | Y | 공유 요약 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `visibility` | `SHARED\|PRIVATE` | Y | 공유 범위 |
| `is_latest_revision` | `boolean` | Y | 최신 기록 리비전 |
| `references_current` | `boolean` | Y | 참조 설계의 현재성 |
| `created_at` | `Time` | Y | 작성 시각 |
| `previous_id` | `ID` | N | 수정 대상 리비전. 최초 생성은 생략 |
| `plan_revision_id` | `ID` | N | 당시 계획 |
| `source_packet_id` | `ID` | N | 근거 패킷 |
| `detail` | `string` | Y | 명시적으로 제출한 상세 기록 |
| `design_refs` | [VersionRef[]](00-common.md#schema-versionref) | Y | 당시 참조 버전 |

```json
{
  "id": "801",
  "root_id": "801",
  "revision_no": 1,
  "task_id": "501",
  "kind": "HANDOVER",
  "summary": "로그인 UI 연결 완료",
  "author_account_id": "11",
  "visibility": "SHARED",
  "is_latest_revision": true,
  "references_current": true,
  "created_at": "2026-10-23T03:00:00Z",
  "plan_revision_id": "511",
  "source_packet_id": "701",
  "detail": "오류 응답은 승인된 message 필드로 표시합니다.",
  "design_refs": [
    {
      "design_item_id": "301",
      "design_item_version_id": "311"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `revision_conflict` | 같은 previous_id에 이미 후속 리비전 있음 |

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "같은 previous_id에 이미 후속 리비전 있음"
  }
}
```

#### 처리 규칙

- 최초 작성은 previous_id를 생략한다. 수정은 본인 기록 previous_id를 참조하는 새 불변 리비전이다.
- 작성자·토큰·프로젝트를 서버가 결정한다. 권한·공유 변경도 새 리비전으로 남긴다.
- DECISION은 공유 판단 기록이며 승인 설계가 아니다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a05"></a>

## CTX-A05. 작업 설계 근거 추천

### POST `/api/projects/{id}/design-reference-suggestions`

작업 설계 근거 추천 기능을 제공한다.

관련 요구사항: TRC-04·06  
담당: BE: 변준성 / AI: 양지훈(근거 추천) / 박상은(검색) / FE: 박민용  
상태: 담당자 검토

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `request` | `string` | Y | 개발 요청 |
| `task_id` | `ID` | N | 관련 작업 |

```json
{
  "request": "로그인 화면을 구현하려고 합니다.",
  "task_id": "501"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `candidates` | [DesignSuggestion[]](00-common.md#schema-designsuggestion) | Y | 추천 항목과 이유 |

```json
{
  "candidates": [
    {
      "design_item_version": {
        "id": "311",
        "design_item_id": "301",
        "key": "API-001",
        "kind": "API_CONTRACT",
        "version_no": 1,
        "status": "APPROVED",
        "schema_version": 1,
        "content": {
          "method": "POST",
          "path": "/api/auth/login",
          "description": "이메일·비밀번호 로그인"
        },
        "source": {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      },
      "reason": "로그인 화면과 연결된 API 계약입니다.",
      "sources": [
        {
          "document_id": "201",
          "document_version_id": "211",
          "version_no": 1,
          "location": "3. 계정 관리 / ACC-02"
        }
      ]
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | 추천 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "추천 실패"
  }
}
```

#### 처리 규칙

- 추천과 확정 관계를 구분한다. 추천 조회는 계획·검증 상태를 바꾸지 않는다.
- 확정 시 새 계획 리비전에 참조를 기록하고 필요하면 재검증한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ctx-a06"></a>

## CTX-A06. 컨텍스트 조회 이력

### GET `/api/projects/{id}/context-access-logs`

누가 어떤 설계 버전·패킷·기록을 봤는지 이력을 조회한다. 도구 호출 감사 기록(mcp_audit_log, MCP-08·09)과 다르다 — 그쪽은 호출·결과·지연, 여기는 **본 대상과 참조 버전**이다.

관련 요구사항: CTX-05·06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 담당자 제안 (S15P31A108-165)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 사람 세션의 팀장만 조회할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | N | 작업 필터. 없거나 다른 프로젝트 작업이면 0건 |
| `tool` | `string` | N | 도구·API 이름 필터. 예: `get_design_changes` |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ContextAccessLog[]](00-common.md#schema-contextaccesslog) | Y | 최신순 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "901",
      "account_id": "11",
      "agent_token_id": "31",
      "task_id": "501",
      "tool": "get_design_changes",
      "target_kind": "BASELINE",
      "target_id": "401",
      "baseline_id": "401",
      "accessed_at": "2026-10-27T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "forbidden",
    "message": "팀장만 할 수 있습니다"
  }
}
```

#### 처리 규칙

- 기록 지점: 설계 내용을 돌려주는 조회 — `get_development_context`(패킷 생성), `read_context`, `get_design_changes`, CTX-A01, CTX-A03. 검색 목록(`find_context`, CTX-A02)은 감사 기록이 덮으므로 남기지 않는다.
- 조회와 같은 트랜잭션에서 기록한다. 조회가 성공하면 이력이 반드시 한 건 있다.
- 호출 계정은 항상 기록한다. 에이전트 호출이면 토큰 소유 계정과 토큰 번호를 함께 남긴다.
- 요청 문장·기록 원문·토큰 값은 기록하지 않는다. 번호만 남긴다.
- 이력은 수정·삭제하지 않는다. 작업·기준·토큰은 같은 프로젝트 것만 저장한다(DB 복합 FK). 다른 프로젝트 작업 번호가 오면 작업 칸을 비워 저장한다.
- 필터는 페이지를 자르기 전에 건다. 다른 프로젝트의 `task_id`는 오류 없이 0건이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="bee-a01"></a>

## BEE-A01. 비비에게 질문

### POST `/api/projects/{id}/bee/questions`

비비에게 질문 기능을 제공한다.

관련 요구사항: BEE-02~04·07  
담당: BE: 오서현(문서 조회·생성 연결) / AI: 박상은 / FE: 박상은  
상태: 서버 연결 담당 검토 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `question` | `string` | Y | 프로젝트 설계 질문 |
| `task_id` | `ID` | N | 관련 작업 |
| `scope` | `APPROVED\|INCLUDE_DRAFTS` | N | 찾는 범위. 기본 APPROVED. INCLUDE_DRAFTS는 원문 RAG 확장의 DOCUMENT_REVIEW에 대응. 수정안 요청 |

```json
{
  "question": "로그인 오류 응답은 어떻게 처리하나요?",
  "task_id": "501"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 호출 시 필수 모델 |
| `text` | `string` | Y | 응답 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `unresolved` | `string[]` | Y | 미확정 사항 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |
| `answer_id` | `ID` | Y | 답변 ID. BEE-A03 피드백에 씀. 수정안 요청 |
| `citations` | [BeeCitation[]](00-common.md#schema-beecitation) | Y | 본문 [n]과 잇는 근거 문단. 없으면 []. 수정안 요청 |
| `grounded` | `boolean` | Y | 근거를 하나 이상 찾았는지. 수정안 요청 |

```json
{
  "origin": "MODEL",
  "model": "configured-model",
  "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
  "sources": [
    {
      "document_id": "201",
      "document_version_id": "211",
      "version_no": 1,
      "location": "3. 계정 관리 / ACC-02"
    }
  ],
  "unresolved": [],
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | 응답·폴백 모두 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "응답·폴백 모두 실패"
  }
}
```

#### 처리 규칙

- 현재 승인 설계·관련 작업을 읽고 출처와 미확정 사항을 함께 응답한다.
- 모델 장애 시 템플릿 응답은 origin=TEMPLATE다. 실제 사용량을 만든 것으로 표시하지 않는다.
- 장기 대화 저장은 이 API 계약에 포함하지 않는다. 공유할 판단은 기록 API로 제출한다.

#### 원문 RAG 확장 — 2026-10-27

BE 저장/검색·실행 메타 담당은 오서현이다. 내부 청킹은 [AI-A09](03-ai-internal.md#ai-a09)를 따른다. 이 절은 원문 RAG 연동 시 적용할 확장 계약이며 현재 구현 완료를 뜻하지 않는다.

- 요청에 `mode?: APPROVED|DOCUMENT_REVIEW`(기본 APPROVED), `document_version_ids?: ID[]`를 추가한다. DOCUMENT_REVIEW는 명시 목록 필수이며 BE가 현재 접근 권한을 재검사한다. APPROVED는 사용자 목록을 받지 않고 현재 baseline에서 정한다.
- RAG 응답은 기존 text/sources/unresolved/usage에 `mode`, `document_version_ids`, APPROVED의 `baseline_id`를 추가한다. DOCUMENT_REVIEW 답변은 미승인 검토임을 표시한다. 내부 citations는 허용 evidence 집합으로 검증 후 기존 Source 형식에 매핑한다.
- 미색인/프로필 미준비는 HTTP 409 `index_not_ready`, 최종 검사에서 baseline 변경은 HTTP 409 `baseline_changed`. 예전 승인본으로 대체하거나 자동 무한 재생성하지 않는다. 정상 검색 0건은 오류와 구분하고 근거 부족을 안내한다.
- 기존 FE 계약은 확장 구현 시 함께 반영한다. 업로드/분석 상태 계약과 아직 미합의인 업로드 정책은 이 변경으로 덮어쓰지 않는다.

#### 화면 수정안 요청 — 2026-10-28

- 비비 대화 모달(단축 바 가운데)은 답 속 번호가 오른쪽 '이 답의 근거' 문단과 이어지고 찾은 문장을 칠해서 보인다. citations는 내부 citations(evidence_id, claim)를 검증한 뒤 문서·버전·행 범위·인용 문장으로 바꾼 것이다.
- 근거를 하나도 찾지 못하면 grounded=false와 '찾지 못했어요' 고정 답을 주고 짐작해 답하지 않는다.
- 답변마다 answer_id를 발급해 BEE-A03 피드백과 잇는다. 대화 본문은 저장하지 않는다(Q13).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="bee-a02"></a>

## BEE-A02. 작업 역할 분담 추천

### POST `/api/projects/{id}/assignment-suggestions`

작업 역할 분담 추천 기능을 제공한다.

관련 요구사항: BEE-10  
담당: BE: 변준성 / AI: 박상은 / FE: 박상은  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_ids` | `ID[]` | Y | 추천 대상 작업 |

```json
{
  "task_ids": [
    "501"
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `suggestions` | [AssignmentSuggestion[]](00-common.md#schema-assignmentsuggestion) | Y | 담당 후보 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | 추천 이유·출처 |

```json
{
  "suggestions": [
    {
      "task_id": "501",
      "member_id": "21",
      "reason": "backend 담당 영역과 일치합니다."
    }
  ],
  "generation": {
    "origin": "MODEL",
    "model": "configured-model",
    "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "usage": [
      {
        "request_key": "llm-validate-601-attempt-1",
        "feature": "TASK_VALIDATION",
        "model": "configured-model",
        "input_tokens": 1200,
        "output_tokens": 300,
        "latency_ms": 1800,
        "failed": false,
        "estimated_cost_amount": "0.0042",
        "currency": "USD",
        "tokens_known": true
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | 분석 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "분석 실패"
  }
}
```

#### 처리 규칙

- 같은 프로젝트의 작업·활성 팀원만 분석한다.
- 추천으로 작업 담당자를 자동 변경하지 않는다. 확정은 TSK-A06이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="bee-a03"></a>

## BEE-A03. 비비 답변 피드백

### POST `/api/projects/{id}/bee/answers/{answer_id}/feedback`

답변마다 '도움이 됐어요 / 근거가 틀려요'를 남긴다.

관련 요구사항: BEE-02 (화면 PNL-12-v2-bibi-hotbar)  
담당: BE: 오서현 / AI: 박상은 / FE: 박상은  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `answer_id` | `ID` | Y | BEE-A01 answer_id |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `rating` | `HELPFUL\|WRONG_SOURCE` | Y | 도움이 됐어요 / 근거가 틀려요 |
| `comment` | `string` | N | 덧붙일 말 |

```json
{
  "rating": "WRONG_SOURCE"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 본인이 받은 답변에만 남긴다. 같은 답변에 다시 보내면 덮어쓴다.
- 대화 본문은 저장하지 않는다. 피드백에는 답변 ID·근거 문서 버전만 남긴다. 활용 방법은 Q13이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="bee-a04"></a>

## BEE-A04. 비비 현재 상태

### GET `/api/projects/{id}/bee/status`

HUD 팀 목록의 비비 줄에 비비가 지금 하는 일을 보여 준다.

관련 요구사항: MON-07 (화면 SCR-06-v2-workspace-hotbar)  
담당: BE: 오서현 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `activity` | `DOCUMENT_REVIEW\|MEETING\|IDLE` | Y | 지금 하는 일 |
| `target_type` | `DOCUMENT_VERSION\|MEETING` | N | 대상 종류 |
| `target_id` | `ID` | N | 대상 |
| `label` | `string` | N | 표시 문구. 예: '알림 API 명세서 검토 중' |

```json
{
  "activity": "DOCUMENT_REVIEW",
  "target_type": "DOCUMENT_VERSION",
  "target_id": "212",
  "label": "알림 API 명세서 검토 중"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 진행 중인 문서 검토(DOC-A13 RUNNING)나 OPEN 회의에서 계산한다. 여러 개면 가장 최근 것 하나를 준다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a01"></a>

## MTG-A01. 회의 생성 (폐지 예정)

### POST `/api/projects/{id}/meetings`

회의 생성 기능을 제공한다.

관련 요구사항: MTG-04  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `title` | `string` | Y | 회의 제목 |
| `change_proposal_id` | `ID` | N | 관련 변경 제안 |
| `participant_member_ids` | `ID[]` | Y | 프로젝트 참여자 |

```json
{
  "title": "로그인 계약 변경 협의",
  "change_proposal_id": "71",
  "participant_member_ids": [
    "21",
    "22"
  ]
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 회의 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `title` | `string` | Y | 제목 |
| `change_proposal_id` | `ID` | N | 연결 제안 |
| `participants` | [Participant[]](00-common.md#schema-participant) | Y | 참여자 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "901",
  "project_id": "101",
  "title": "로그인 계약 변경 협의",
  "change_proposal_id": "71",
  "participants": [
    {
      "member_id": "21",
      "status": "ACCEPTED"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 변경 제안 협의와 같은 참여자·기록·알림 구조를 사용한다.
- 관련자 근거 없이 전체 팀원을 자동 호출하지 않는다.
- (수정안 요청) 사람은 회의를 만들지 않는다. 서버가 작업 충돌을 감지하면 회의를 연다. 기본안: AI-A02 검증에서 담당자가 다른 활성 작업 사이의 BLOCKING CONFLICT가 나오면 origin=BEE_CONFLICT 회의를 만들고, 참여자는 팀장, 그 작업 담당자, 충돌한 설계 항목의 담당 영역 팀원(화면 예: '최팀원 · 화면 문구')이고, 첫 기록은 작업·문서 칩(refs)을 단 비비의 CONFLICT_BRIEF다(Q17).
- (수정안 요청) 같은 작업 묶음에 OPEN 회의가 있으면 새로 만들지 않는다. 참여자에게 MEETING 알림을 보낸다. 이 API는 내부 생성 경로로 바꾸거나 없앤다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a02"></a>

## MTG-A02. 회의 목록

### GET `/api/projects/{id}/meetings`

회의 목록 기능을 제공한다.

관련 요구사항: MTG-04  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |
| `status` | `OPEN\|CLOSED` | N | 회의 상태 필터. 수정안 요청 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Meeting[]](00-common.md#schema-meeting) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "901",
      "project_id": "101",
      "title": "로그인 계약 변경 협의",
      "change_proposal_id": "71",
      "participants": [
        {
          "member_id": "21",
          "status": "ACCEPTED"
        }
      ],
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 조회·변경 대상의 현재 프로젝트 소속과 권한을 검사한다.
- (수정안 요청) 회의가 없으면 화면은 '회의가 없어요'와 비비가 회의를 연다는 안내만 보인다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a03"></a>

## MTG-A03. 회의 상세

### GET `/api/projects/{id}/meetings/{meeting_id}`

회의 상세 기능을 제공한다.

관련 요구사항: MTG-04  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 회의 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `title` | `string` | Y | 제목 |
| `change_proposal_id` | `ID` | N | 연결 제안 |
| `participants` | [Participant[]](00-common.md#schema-participant) | Y | 참여자 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "901",
  "project_id": "101",
  "title": "로그인 계약 변경 협의",
  "change_proposal_id": "71",
  "participants": [
    {
      "member_id": "21",
      "status": "ACCEPTED"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 기록은 MTG-A10으로 페이지 조회한다. 회의 상세에 무제한 전체 기록을 포함하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a04"></a>

## MTG-A04. 회의 참여 요청 (폐지 예정)

### POST `/api/projects/{id}/meetings/{meeting_id}/participants`

회의 참여 요청 기능을 제공한다.

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `member_ids` | `ID[]` | Y | 요청 대상 활성 멤버 |

```json
{
  "member_ids": [
    "22"
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 회의 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `title` | `string` | Y | 제목 |
| `change_proposal_id` | `ID` | N | 연결 제안 |
| `participants` | [Participant[]](00-common.md#schema-participant) | Y | 참여자 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "901",
  "project_id": "101",
  "title": "로그인 계약 변경 협의",
  "change_proposal_id": "71",
  "participants": [
    {
      "member_id": "21",
      "status": "ACCEPTED"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 이미 초대한 대상은 중복 생성하지 않는다.
- 참여 요청 알림의 대상·내용은 회의 서비스가 정해 공통 알림 서비스로 전달한다.
- (수정안 요청) 비비가 연 회의의 참여자는 팀장과 충돌 작업 담당자로 정해지고 수락 절차 없이 참여한다. 팀원 초대 화면은 없앴다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a05"></a>

## MTG-A05. 회의 참여 응답 (폐지 예정)

### PATCH `/api/projects/{id}/meetings/{meeting_id}/participation`

회의 참여 응답 기능을 제공한다.

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER (초대 대상 본인)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `ACCEPTED\|DECLINED` | Y | 참여 의사 |

```json
{
  "status": "ACCEPTED"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 회의 ID |
| `project_id` | `ID` | Y | 프로젝트 |
| `title` | `string` | Y | 제목 |
| `change_proposal_id` | `ID` | N | 연결 제안 |
| `participants` | [Participant[]](00-common.md#schema-participant) | Y | 참여자 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "901",
  "project_id": "101",
  "title": "로그인 계약 변경 협의",
  "change_proposal_id": "71",
  "participants": [
    {
      "member_id": "21",
      "status": "ACCEPTED"
    }
  ],
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `not_invited` | 참여 요청 대상이 아님 |

```json
{
  "error": {
    "code": "not_invited",
    "message": "참여 요청 대상이 아님"
  }
}
```

#### 처리 규칙

- 본인의 참여 상태만 변경한다. 다른 멤버의 참여 의사를 대신 입력하지 않는다.
- (수정안 요청) 비비가 연 회의는 수락 절차가 없어 이 API를 쓰지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a06"></a>

## MTG-A06. 회의 텍스트 기록

### POST `/api/projects/{id}/meetings/{meeting_id}/entries`

회의 텍스트 기록 기능을 제공한다.

관련 요구사항: MTG-06·08  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `text` | `string` | Y | 회의 기록 |

```json
{
  "text": "현재 로그인 응답 필드는 유지하기로 했습니다."
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 기록 ID |
| `meeting_id` | `ID` | Y | 회의 ID |
| `kind` | `TEXT\|BEE_ANALYSIS\|MINUTES` | Y | 기록 종류 |
| `text` | `string` | Y | 기록 본문 |
| `author_account_id` | `ID` | N | 작성 계정. 시스템 생성은 생략 |
| `through_entry_id` | `ID` | N | 분석 마지막 입력 기록 |
| `generation` | [Generation](00-common.md#schema-generation) | N | AI 생성 근거 |
| `created_at` | `Time` | Y | 작성 시각 |

```json
{
  "id": "911",
  "meeting_id": "901",
  "kind": "TEXT",
  "text": "현재 로그인 응답의 오류 필드 설명을 보완합니다.",
  "author_account_id": "11",
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `meeting_closed` | 끝난 회의 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 회의 참여 상태를 검사하고 사람의 텍스트 기록을 저장한다.
- TEXT 기록과 AI 분석·회의록을 entry kind로 구분한다.
- (수정안 요청) 끝난 회의(status=CLOSED)에는 기록을 받지 않는다. 응답 기록에는 author_kind·author_member_id·author_name을 넣어 화면이 내 말은 오른쪽, 다른 사람은 이름과 함께 왼쪽, 비비는 노란 말풍선으로 그린다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a07"></a>

## MTG-A07. 비비의 회의 맥락 분석 (폐지 예정)

### POST `/api/projects/{id}/meetings/{meeting_id}/bee-analysis`

비비의 회의 맥락 분석 기능을 제공한다.

관련 요구사항: MTG-06  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `through_entry_id` | `ID` | Y | 분석할 마지막 기록 |

```json
{
  "through_entry_id": "911"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `origin` | `MODEL\|TEMPLATE` | Y | 생성 출처 |
| `model` | `string` | N | 실제 호출 시 필수 모델 |
| `text` | `string` | Y | 응답 |
| `sources` | [Source[]](00-common.md#schema-source) | Y | 근거 |
| `unresolved` | `string[]` | Y | 미확정 사항 |
| `usage` | [Usage[]](00-common.md#schema-usage) | N | 실제 모델 호출 시 필수 |

```json
{
  "origin": "MODEL",
  "model": "configured-model",
  "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
  "sources": [
    {
      "document_id": "201",
      "document_version_id": "211",
      "version_no": 1,
      "location": "3. 계정 관리 / ACC-02"
    }
  ],
  "unresolved": [],
  "usage": [
    {
      "request_key": "llm-validate-601-attempt-1",
      "feature": "TASK_VALIDATION",
      "model": "configured-model",
      "input_tokens": 1200,
      "output_tokens": 300,
      "latency_ms": 1800,
      "failed": false,
      "estimated_cost_amount": "0.0042",
      "currency": "USD",
      "tokens_known": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | 분석 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "분석 실패"
  }
}
```

#### 처리 규칙

- 해당 회의의 through_entry_id까지 입력을 고정하고 질문·누락 후보를 반환한다.
- 결과는 BEE_ANALYSIS 기록으로 저장하되 설계를 직접 바꾸지 않는다.
- (수정안 요청) 팀장만 부르는 비비 회의 정리(MTG-A11)로 대체한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a08"></a>

## MTG-A08. 회의록 생성 (폐지 예정)

### POST `/api/projects/{id}/meetings/{meeting_id}/minutes`

회의록 생성 기능을 제공한다.

관련 요구사항: MTG-08  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `through_entry_id` | `ID` | Y | 분석 마지막 기록 |

```json
{
  "through_entry_id": "911"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `entry` | [MeetingEntry](00-common.md#schema-meetingentry) | Y | 저장된 MINUTES 기록 |
| `summary` | `string` | Y | 요약 |
| `decisions` | `string[]` | Y | 합의 내용 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 후속 조치 |
| `unresolved` | `string[]` | Y | 미확정 항목 |

```json
{
  "entry": {
    "id": "911",
    "meeting_id": "901",
    "kind": "MINUTES",
    "text": "로그인 오류 응답 설명을 보완하기로 했다.",
    "author_account_id": "11",
    "created_at": "2026-10-23T03:00:00Z"
  },
  "summary": "로그인 오류 응답 설명을 보완하기로 했다.",
  "decisions": [
    "기존 필드 구조는 유지한다."
  ],
  "action_items": [
    {
      "text": "로그인 문서의 오류 응답 설명을 수정한다.",
      "member_id": "21"
    }
  ],
  "unresolved": []
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 502 | `ai_unavailable` | 요약 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "ai_unavailable",
    "message": "요약 실패"
  }
}
```

#### 처리 규칙

- 텍스트 기록을 요약해 MINUTES entry로 저장한다.
- 결정·후속 행동·미확정을 구분한다. MTG-08의 전사 입력 범위는 Q09다.
- (수정안 요청) 회의록 화면은 없앴다. 정리는 MTG-A11이 SUMMARY 기록으로 남긴다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a09"></a>

## MTG-A09. 회의 결정 기반 문서 수정안 (폐지 예정)

### POST `/api/projects/{id}/meetings/{meeting_id}/change-drafts`

회의 결정 기반 문서 수정안 기능을 제공한다.

관련 요구사항: MTG-09  
담당: BE: 오서현 / AI: 양지훈(회의 연결) / 박상은(수정안 생성) / FE: 오서현  
상태: 폐지 예정 · 수정안 요청

#### 인증

`MEMBER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `minutes_entry_id` | `ID` | Y | 해당 회의 MINUTES 기록 |
| `document_id` | `ID` | Y | 수정할 문서 |
| `base_document_version_id` | `ID` | Y | 현재 승인 원문 |

```json
{
  "minutes_entry_id": "912",
  "document_id": "201",
  "base_document_version_id": "211"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `after_content` | `string` | Y | 문서 수정안 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | 미승인 생성 근거 |

```json
{
  "after_content": "# 로그인\n오류 응답 설명을 보완한다.",
  "generation": {
    "origin": "MODEL",
    "model": "configured-model",
    "text": "현재 승인 계약에 따라 이메일과 비밀번호를 입력합니다.",
    "sources": [
      {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "3. 계정 관리 / ACC-02"
      }
    ],
    "unresolved": [],
    "usage": [
      {
        "request_key": "llm-validate-601-attempt-1",
        "feature": "TASK_VALIDATION",
        "model": "configured-model",
        "input_tokens": 1200,
        "output_tokens": 300,
        "latency_ms": 1800,
        "failed": false,
        "estimated_cost_amount": "0.0042",
        "currency": "USD",
        "tokens_known": true
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 원문 기준 변경 |
| 502 | `ai_unavailable` | 초안 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "원문 기준 변경"
  }
}
```

#### 처리 규칙

- 양지훈의 회의 분석에서 박상은의 문서 수정 모듈을 재사용한다.
- 결과는 미승인 초안이다. 변경 제안·사람 팀장 승인을 거쳐 발행한다.
- (수정안 요청) 회의 결과는 변경 제안을 거치지 않고 회의 끝내기(MTG-A13)에서 문서 새 버전으로 바로 반영한다(2026-10-28 사용자 확정).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a10"></a>

## MTG-A10. 회의 기록 목록

### GET `/api/projects/{id}/meetings/{meeting_id}/entries`

회의 기록 목록 기능을 제공한다.

관련 요구사항: MTG-04·06·08  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |
| `after_entry_id` | `ID` | N | 이 기록 뒤의 새 기록만 오래된 순으로. 대화 화면 폴링용. 수정안 요청 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [MeetingEntry[]](00-common.md#schema-meetingentry) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "911",
      "meeting_id": "901",
      "kind": "TEXT",
      "text": "현재 로그인 응답의 오류 필드 설명을 보완합니다.",
      "author_account_id": "11",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- TEXT·BEE_ANALYSIS·MINUTES를 구분해 페이지로 반환한다.
- (수정안 요청) CONFLICT_BRIEF·SUMMARY 기록도 함께 돌려준다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a11"></a>

## MTG-A11. 비비 회의 정리

### POST `/api/projects/{id}/meetings/{meeting_id}/bee-summary`

'비비에게 검토 요청'으로 회의 대화를 충돌·결정·바뀔 문서·할 일로 정리한다.

관련 요구사항: MTG-06·08 (화면 PNL-06-v2-bibi-review, PNL-06-v2-meetings-member)  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `through_entry_id` | `ID` | Y | 정리에 쓸 마지막 기록 |

```json
{
  "through_entry_id": "918"
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 정리 ID |
| `meeting_id` | `ID` | Y | 회의 |
| `entry` | [MeetingEntry](00-common.md#schema-meetingentry) | Y | 회의 기록에 남긴 SUMMARY 기록 |
| `through_entry_id` | `ID` | Y | 정리에 쓴 마지막 기록 |
| `conflicts` | `string[]` | Y | 충돌 |
| `decisions` | `object[]` | Y | 결정 {text, decided_by_member_id?} |
| `document_changes` | [MeetingDocumentChange[]](00-common.md#schema-meetingdocumentchange) | Y | 바뀔 문서와 줄, 근거 발언 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 할 일 |
| `previous_summary_id` | `ID` | N | 수정 요청으로 다시 만든 정리면 이전 정리 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | AI 생성 근거 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "951",
  "meeting_id": "901",
  "through_entry_id": "918",
  "conflicts": [
    "TASK-002와 TASK-003이 초대 만료 기간을 다르게 가정함"
  ],
  "decisions": [
    {"text": "초대 만료는 7일로 통일", "decided_by_member_id": "21"}
  ],
  "document_changes": [
    {
      "document_id": "203",
      "base_version_id": "231",
      "locator": {
        "type": "text_lines",
        "start_line": 40,
        "end_line": 40
      },
      "before": "초대는 3일 뒤 만료된다.",
      "after": "초대는 7일 뒤 만료된다.",
      "evidence_entry_ids": [
        "915",
        "917"
      ]
    }
  ],
  "action_items": [
    {
      "text": "TASK-003 만료 상수 수정",
      "member_id": "23"
    }
  ],
  "created_at": "2026-10-28T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `meeting_closed` | 끝난 회의 |
| 502 | `ai_unavailable` | AI 응답 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 팀장만 부른다. 팀원 화면은 버튼을 끄고 '팀장만 요청할 수 있어요'를 보이며, 서버도 403으로 거절한다.
- 대화를 바탕으로 정리해 SUMMARY 기록으로 남기고 노란 말풍선으로 보인다(AI-A08 SUMMARY).
- document_changes.after는 회의를 끝낼 때 그대로 들어갈 문장이고, 근거 발언은 이 회의의 실제 기록 ID만 쓴다.
- 설계를 직접 바꾸지 않는다. 반영은 MTG-A13이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a12"></a>

## MTG-A12. 회의 정리 수정 요청

### POST `/api/projects/{id}/meetings/{meeting_id}/bee-summary/{summary_id}/revise`

정리가 틀리면 팀장이 고칠 점을 적어 비비에게 다시 정리하게 한다.

관련 요구사항: MTG-08 (화면 PNL-06-v2-bibi-review)  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |
| `summary_id` | `ID` | Y | 고칠 정리 |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `feedback` | `string` | Y | 고칠 점 |

```json
{
  "feedback": "만료 기간은 7일이 아니라 14일로 정했어."
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 정리 ID |
| `meeting_id` | `ID` | Y | 회의 |
| `entry` | [MeetingEntry](00-common.md#schema-meetingentry) | Y | 회의 기록에 남긴 SUMMARY 기록 |
| `through_entry_id` | `ID` | Y | 정리에 쓴 마지막 기록 |
| `conflicts` | `string[]` | Y | 충돌 |
| `decisions` | `object[]` | Y | 결정 {text, decided_by_member_id?} |
| `document_changes` | [MeetingDocumentChange[]](00-common.md#schema-meetingdocumentchange) | Y | 바뀔 문서와 줄, 근거 발언 |
| `action_items` | [ActionItem[]](00-common.md#schema-actionitem) | Y | 할 일 |
| `previous_summary_id` | `ID` | N | 수정 요청으로 다시 만든 정리면 이전 정리 |
| `generation` | [Generation](00-common.md#schema-generation) | Y | AI 생성 근거 |
| `created_at` | `Time` | Y | 생성 시각 |

```json
{
  "id": "952",
  "meeting_id": "901",
  "through_entry_id": "918",
  "conflicts": [
    "TASK-002와 TASK-003이 초대 만료 기간을 다르게 가정함"
  ],
  "decisions": [
    {"text": "초대 만료는 7일로 통일", "decided_by_member_id": "21"}
  ],
  "document_changes": [
    {
      "document_id": "203",
      "base_version_id": "231",
      "locator": {
        "type": "text_lines",
        "start_line": 40,
        "end_line": 40
      },
      "before": "초대는 3일 뒤 만료된다.",
      "after": "초대는 7일 뒤 만료된다.",
      "evidence_entry_ids": [
        "915",
        "917"
      ]
    }
  ],
  "action_items": [
    {
      "text": "TASK-003 만료 상수 수정",
      "member_id": "23"
    }
  ],
  "created_at": "2026-10-28T03:00:00Z",
  "previous_summary_id": "951"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `meeting_closed` | 끝난 회의 |
| 409 | `summary_outdated` | 최신 정리가 아님 |
| 502 | `ai_unavailable` | AI 응답 실패 |
| 504 | `ai_timeout` | 시간 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 이전 정리와 피드백을 입력으로 다시 정리한다. 새 정리가 최신이 되고 이전 정리는 기록에 남는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a13"></a>

## MTG-A13. 회의 끝내기

### POST `/api/projects/{id}/meetings/{meeting_id}/close`

팀장이 비비 정리를 확인하고 회의를 끝낸다. 공용 문서가 새 버전으로 바로 바뀐다.

관련 요구사항: MTG-09 (화면 PNL-06-v2-draft, PNL-06-v2-draft-result)  
담당: BE: 오서현 / AI: 박상은(문서 반영) / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`LEADER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `meeting_id` | `ID` | Y | 회의 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `summary_id` | `ID` | Y | 팀장이 확인한 최신 정리 |
| `expected_base_version_ids` | `ID[]` | Y | 정리의 document_changes.base_version_id 목록 |

```json
{
  "summary_id": "951",
  "expected_base_version_ids": [
    "231"
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `meeting` | [Meeting](00-common.md#schema-meeting) | Y | status=CLOSED인 회의 |
| `document_versions` | [DocumentVersion[]](00-common.md#schema-documentversion) | Y | 새로 만든 승인본. source_meeting_id가 이 회의 |
| `notified_member_ids` | `ID[]` | Y | 알림을 받은 관련 작업 담당자 |

```json
{
  "meeting": {
    "id": "901",
    "status": "CLOSED",
    "closed_at": "2026-10-28T04:00:00Z"
  },
  "document_versions": [
    {
      "id": "232",
      "document_id": "203",
      "version_no": 2,
      "status": "APPROVED",
      "source_meeting_id": "901"
    }
  ],
  "notified_member_ids": [
    "23"
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `summary_outdated` | 정리 뒤 새 기록이 있거나 최신 정리가 아님 |
| 409 | `version_conflict` | 문서 승인본이 바뀜 |
| 409 | `meeting_closed` | 이미 끝난 회의 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 팀장이 끝내는 것이 승인이다. 변경 제안을 거치지 않는다(2026-10-28 사용자 확정).
- 한 트랜잭션에서 document_changes를 적용한 새 문서 버전을 APPROVED로 만들고 회의를 CLOSED로 바꾼다. 같은 문서의 이전 승인본은 SUPERSEDED다.
- task_ids의 담당자에게 바뀐 기준을 DOCUMENT_VERSION 알림으로 보내고 활동에 남긴다. 바뀐 문서는 MCP-03에도 보인다.
- 끝난 회의는 읽기만 한다(MTG-A06 409 meeting_closed).
- 기준 버전을 올리는지, 관련 작업을 재검증으로 돌리는지, 잘못 반영됐을 때 되돌리는 방법은 Q16이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-d01"></a>

## MON-D01. 문서 승인 현황

### GET `/api/projects/{id}/documents/summary`

문서 승인 현황 기능을 제공한다.

관련 요구사항: MON-01  
담당: BE: 오서현 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseline` | [BaselineSummary](00-common.md#schema-baselinesummary) | N | 현재 기준 |
| `by_kind` | [DocumentCount[]](00-common.md#schema-documentcount) | Y | 문서 유형별 승인 상태 |

```json
{
  "baseline": {
    "id": "401",
    "version_no": 1,
    "created_at": "2026-10-23T03:00:00Z"
  },
  "by_kind": [
    {
      "kind": "REQUIREMENTS",
      "draft_count": 0,
      "approved_count": 1
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 필수 문서 상태와 현재 기준을 제공한다. 미승인 초안과 승인본을 구분한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-c01"></a>

## MON-C01. 변경 제안 현황

### GET `/api/projects/{id}/change-proposals/summary`

변경 제안 현황 기능을 제공한다.

관련 요구사항: MON-02  
담당: BE: 오서현 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `by_status` | [StateCount[]](00-common.md#schema-statecount) | Y | 변경 제안 상태별 건수 |
| `review_required_count` | `integer` | Y | 검토 필요 건수 |

```json
{
  "by_status": [
    {
      "status": "PROPOSED",
      "count": 2
    },
    {
      "status": "IN_REVIEW",
      "count": 1
    }
  ],
  "review_required_count": 3
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 문서 도메인 소유의 현황 조회다. 작업 통계와 합치는 중간 API를 만들지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-t01"></a>

## MON-T01. 작업 상태 현황

### GET `/api/projects/{id}/tasks/summary`

작업 상태 현황 기능을 제공한다.

관련 요구사항: MON-03  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `by_status` | [StateCount[]](00-common.md#schema-statecount) | Y | 작업 상태별 건수 |
| `by_waiting_reason` | [WaitingCount[]](00-common.md#schema-waitingcount) | Y | 대기 이유별 건수 |

```json
{
  "by_status": [
    {
      "status": "READY",
      "count": 1
    }
  ],
  "by_waiting_reason": [
    {
      "waiting_reason": "REVALIDATION",
      "count": 1
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 작업 상태·대기 이유만 집계한다. 검증 상태와 혼용하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-v01"></a>

## MON-V01. 검증 상태 현황

### GET `/api/projects/{id}/validations/summary`

검증 상태 현황 기능을 제공한다.

관련 요구사항: MON-04  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `by_status` | [StateCount[]](00-common.md#schema-statecount) | Y | 검증 상태별 건수 |

```json
{
  "by_status": [
    {
      "status": "PASS",
      "count": 2
    },
    {
      "status": "STALE",
      "count": 1
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 현재 계획의 검증 상태를 기준으로 집계한다. 과거 실행 시도 수와 구분한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a02"></a>

## MON-A02. 프로젝트 AI 사용량·추정 비용

### GET `/api/projects/{id}/usage`

프로젝트 AI 사용량·추정 비용 기능을 제공한다.

관련 요구사항: MON-05, NFR-09  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from` | `Time` | Y | 집계 시작 포함 |
| `to` | `Time` | Y | 집계 끝 미포함 |
| `task_id` | `ID` | N | 작업 필터 |
| `validation_id` | `ID` | N | 검증 필터 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `from` | `Time` | Y | 집계 시작. 포함 |
| `to` | `Time` | Y | 집계 끝. 미포함 |
| `input_tokens` | `integer` | Y | 총 입력 토큰 |
| `output_tokens` | `integer` | Y | 총 출력 토큰 |
| `call_count` | `integer` | Y | 중복 제거한 호출 수 |
| `estimated_cost_amount` | `string` | N | 추정 비용. 단가 없으면 생략 |
| `currency` | `string` | N | 통화 |
| `by_feature` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 기능별 집계 |
| `by_task` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 작업별. key는 task ID |
| `by_validation` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 검증별. key는 validation ID |
| `by_agent` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 에이전트별. 웹 직접 호출은 key WEB. 수정안 요청 |
| `by_day` | [UsageBucket[]](00-common.md#schema-usagebucket) | Y | 한국 시간 날짜별. 수정안 요청 |

```json
{
  "from": "2026-10-23T03:00:00Z",
  "to": "2026-10-24T03:00:00Z",
  "input_tokens": 1200,
  "output_tokens": 300,
  "call_count": 1,
  "estimated_cost_amount": "0.0042",
  "currency": "USD",
  "by_feature": [
    {
      "key": "TASK_VALIDATION",
      "input_tokens": 1200,
      "output_tokens": 300,
      "call_count": 1,
      "estimated_cost_amount": "0.0042"
    }
  ],
  "by_task": [
    {
      "key": "501",
      "input_tokens": 1200,
      "output_tokens": 300,
      "call_count": 1,
      "estimated_cost_amount": "0.0042"
    }
  ],
  "by_validation": [
    {
      "key": "601",
      "input_tokens": 1200,
      "output_tokens": 300,
      "call_count": 1,
      "estimated_cost_amount": "0.0042"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- from < to를 검사한다. 필터 없이 프로젝트 전체·기능별 비용을 조회한다.
- 작업에 연결되지 않은 문서 분석·대화 호출도 전체·기능별 집계에 포함한다.
- request_key로 같은 모델 호출을 한 번만 합산하고 실패·재시도도 실제 호출만큼 반영한다.
- 추정 비용과 사용량을 구분한다. 단가가 없으면 비용을 생략하고 0원으로 표시하지 않는다.
- (수정안 요청) by_agent는 caller=AGENT 호출을 agent_session_id별로, 웹에서 직접 한 호출(문서 분석·비비 질문·회의 분석)을 key WEB 한 줄로 묶는다. by_day는 한국 시간 날짜별이고 대시보드의 '날짜별 AI 호출' 막대가 쓴다. 비용을 팀장만 볼지는 Q21이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a03"></a>

## MON-A03. 작업·설계·검증 관계

### GET `/api/projects/{id}/relations`

작업·설계·검증 관계 기능을 제공한다.

관련 요구사항: MON-06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `task_id` | `ID` | N | 기준 작업 |
| `design_item_id` | `ID` | N | 기준 설계 항목 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `nodes` | [RelationNode[]](00-common.md#schema-relationnode) | Y | 관계 객체 |
| `edges` | [RelationEdge[]](00-common.md#schema-relationedge) | Y | 관계 |

```json
{
  "nodes": [
    {
      "id": "501",
      "type": "TASK",
      "label": "로그인 화면 구현"
    }
  ],
  "edges": [
    {
      "from_id": "501",
      "from_type": "TASK",
      "to_id": "301",
      "to_type": "DESIGN_ITEM",
      "relation": "IMPLEMENTS",
      "confirmed": true
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 최소 하나의 기준 ID를 받아 기본 관계를 조회하는 계약이다.
- 명시 관계와 AI 후보는 confirmed로 구분한다. 과거 관계는 당시 계획·버전과 연결한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a04"></a>

## MON-A04. 프로젝트 활동 타임라인

### GET `/api/projects/{id}/activities`

프로젝트 활동 타임라인 기능을 제공한다.

관련 요구사항: MON-08  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `type` | `string` | N | 이벤트 유형 |
| `actor_type` | `HUMAN\|AGENT\|BEE\|SYSTEM` | N | 행위자 종류 필터. 수정안 요청 |
| `member_id` | `ID` | N | 사람 또는 그 멤버의 에이전트 필터. 수정안 요청 |
| `agent_session_id` | `ID` | N | 에이전트 필터. 수정안 요청 |
| `change_proposal_id` | `ID` | N | 한 변경 제안의 지나온 길(올림·검토 시작·검토 요청·의견·승인). 수정안 요청 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Activity[]](00-common.md#schema-activity) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "id": "1001",
      "type": "TASK_STARTED",
      "actor_account_id": "11",
      "task_id": "501",
      "change_proposal_id": "71",
      "baseline_id": "401",
      "result": "SUCCESS",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 각 도메인이 생성한 활동을 공통 중복 식별값으로 저장·조회한다.
- 이벤트 종류와 처리 결과를 별도로 표시한다.
- (수정안 요청) 활동 타임라인은 사람과 에이전트를 아바타로 나눠 전체 / 사람 / 에이전트별로 보인다. Activity의 actor_type·agent_session_id·actor_name으로 그린다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a05"></a>

## MON-A05. 에이전트 현재 작업 현황

### GET `/api/projects/{id}/agents/work-status`

에이전트 현재 작업 현황 기능을 제공한다.

관련 요구사항: MON-07, AGT-07  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: 설계 초안

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `member_id` | `ID` | N | 멤버 필터 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [AgentWork[]](00-common.md#schema-agentwork) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |

```json
{
  "items": [
    {
      "member_id": "21",
      "agent_session_id": "51",
      "current_task_id": "501",
      "task_status": "IN_PROGRESS",
      "validation_status": "PASS"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 연결 정보는 AGT-A05, 명시 보고된 작업·검증·대기는 이 API로 나눠 제공한다.
- 에이전트 보고가 없으면 실행 중·완료를 추정하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a06"></a>

## MON-A06. 내 접속 상태 보고

### PUT `/api/projects/{id}/presence`

워크스페이스를 연 동안 내 위치를 주기적으로 보고한다.

관련 요구사항: MON-07 (화면 SCR-06-v2-workspace-hotbar)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `zone` | `string` | N | 사무실 안 위치. 허용 값은 FE와 합의 |
| `seat` | `string` | N | 자리 식별값 |
| `panel` | `string` | N | 열어 둔 메뉴. 공개 여부는 Q18 |
| `activity` | `object` | N | 지금 하는 일 {action: VIEWING\|EDITING, target_type, target_id}. HUD의 '변경 제안 검토 중', 'TASK-002 계획 작성 중' |

```json
{
  "zone": "desk",
  "seat": "A-3"
}
```

#### Response

HTTP `204`.

응답 본문 없음.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 클라이언트가 워크스페이스를 연 동안 일정 주기로 보낸다. 주기와 TTL은 Q18이다.
- 마지막 보고가 TTL 안이면 online이다. 탭을 닫으면 보고가 끊겨 TTL 뒤 오프라인이 된다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-a07"></a>

## MON-A07. 팀원 접속 현황

### GET `/api/projects/{id}/presence`

HUD 팀 목록에 팀원마다 접속 점과 위치를 보인다.

관련 요구사항: MON-07 (화면 SCR-06-v2-workspace-hotbar)  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Presence[]](00-common.md#schema-presence) | Y | 활성 멤버마다 하나 |

```json
{
  "items": [
    {
      "member_id": "22",
      "online": true,
      "last_seen_at": "2026-10-28T03:00:00Z",
      "zone": "desk",
      "seat": "A-3"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 같은 프로젝트 활성 멤버만 돌려준다.
- 사람이 하는 일은 TSK-A02(진행 중 작업), 에이전트는 MON-A05로 프론트가 합친다.
- 전송은 폴링으로 시작한다. SSE·WebSocket은 Q18이다.
- (수정안 요청) 오프라인이면 마지막 위치·하는 일과 last_seen_at을 그대로 준다. 화면은 '오프라인 · 1시간 전 · TASK-002 계획 작성 중'처럼 보인다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ntf-a01"></a>

## NTF-A01. 내 알림함

### GET `/api/me/notifications`

내 알림함 기능을 제공한다.

관련 요구사항: NTF-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `project_id` | `ID` | N | 프로젝트 필터 |
| `unread` | `boolean` | N | 읽지 않은 알림만 |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 페이지 크기. 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [Notification[]](00-common.md#schema-notification) | Y | 조회된 항목 |
| `has_more` | `boolean` | Y | 다음 페이지 존재 |
| `next_cursor` | `string` | N | 다음 페이지가 있으면 반환 |
| `unread_count` | `integer` | Y | project_id 범위의 읽지 않은 알림 수. unread·cursor와 상관없음. 벨 배지. 수정안 요청 |

```json
{
  "items": [
    {
      "id": "1101",
      "project_id": "101",
      "type": "REVALIDATION_REQUIRED",
      "title": "작업 재검증 필요",
      "body": "참조한 설계가 변경되었습니다.",
      "target_type": "TASK",
      "target_id": "501",
      "created_at": "2026-10-23T03:00:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 세션 계정 수신 알림만 반환한다. 읽음과 문제 해결 상태는 다르다.
- (수정안 요청) 새 알림: 비비 문서 검토 끝남(등록자), 문서 승인 요청(팀장), 문서 승인·반려(등록자), 회의가 끝나 문서가 바뀜(관련 작업 담당자)은 DOCUMENT_VERSION. 기준 v1 생김(활성 팀원)은 BASELINE, 비비가 연 회의(참여자)는 MEETING, 작업 담당자 변경(새 담당자)은 TASK, 회의에 새 기록이 올라옴(다른 참여자)은 MEETING.
- (수정안 요청) 대상이 지워졌거나 권한이 없으면 화면은 404를 받고 '찾을 수 없어요'를 보인 뒤 그 알림을 읽음 처리한다(기본안).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ntf-a02"></a>

## NTF-A02. 알림 읽음 처리

### POST `/api/me/notifications/read`

알림 읽음 처리 기능을 제공한다.

관련 요구사항: NTF-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안 · 수정안 요청 반영

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ids` | `ID[]` | N | 읽음 처리할 본인 알림. all과 둘 중 하나 |
| `all` | `boolean` | N | true면 본인 알림 전체(또는 project_id 범위)를 읽음 처리. 수정안 요청 |
| `project_id` | `ID` | N | all=true일 때 범위. 수정안 요청 |

```json
{
  "ids": [
    "1101"
  ]
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `updated_count` | `integer` | Y | 새로 읽음 처리한 건수 |

```json
{
  "updated_count": 1
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 모든 ID의 본인 소유를 검사한 뒤 일괄 처리한다. 타인 ID는 404이며 부분 처리하지 않는다.
- 이미 읽은 알림은 유지하고 다시 요청해도 중복 갱신하지 않는다.
- (수정안 요청) '모두 읽음'은 이동 없이 all=true로 읽음 처리하고 벨 배지를 지운다. 알림 하나를 누르면 그 ID만 읽음 처리한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="sys-a01"></a>

## SYS-A01. backend 기동 확인

### GET `/api/ping`

backend 기동 확인 기능을 제공한다.

관련 요구사항: 운영·기동 확인  
담당: BE: 전진 / AI: — / FE: —  
상태: 구현된 응답

#### 인증

`PUBLIC`. 세션 없이 호출할 수 있다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ok` | `boolean` | Y | backend 프로세스 응답 |
| `service` | `string` | Y | 서비스명 |
| `started_at` | `Time` | Y | 현재 프로세스 시작 시각 |
| `server_time` | `Time` | Y | 서버 현재 시각 |

```json
{
  "ok": true,
  "service": "beez-backend",
  "started_at": "2026-10-23T03:00:00Z",
  "server_time": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 500 | `internal` | 예상하지 못한 처리 실패 |

```json
{
  "error": {
    "code": "internal",
    "message": "예상하지 못한 처리 실패"
  }
}
```

#### 처리 규칙

- DB·AI 모델·도메인 기능 전체의 정상 동작을 보증하는 값이 아니다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="sys-a02"></a>

## SYS-A02. backend·AI 연결 확인

### GET `/api/system/status`

backend·AI 연결 확인 기능을 제공한다.

관련 요구사항: 운영·외부 서비스 오류 격리  
담당: BE: 전진 / AI: — / FE: —  
상태: 응답 구현 / 운영 접근 정책 검토

#### 인증

`USER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ok` | `boolean` | Y | backend 응답 |
| `service` | `string` | Y | 서비스명 |
| `ai_reachable` | `boolean` | Y | AI health 호출 성공 |
| `time` | `Time` | Y | 서버 시각 |

```json
{
  "ok": true,
  "service": "beez-backend",
  "ai_reachable": true,
  "time": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- AI health가 실패해도 200과 ai_reachable=false를 반환한다. 모델 분석 성공을 의미하지 않는다.
- 운영 접근은 USER 기준안이다. 현재 인증 구현과 노출 정책은 Q04에서 반영한다.

[공통 규약·권한·담당자 목록](00-common.md)

---
