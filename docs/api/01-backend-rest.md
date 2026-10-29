# BEEZ Backend REST API

버전: 0.6 · 갱신일: 2026-10-28 · 개발 계약 초안

웹 클라이언트가 backend를 호출하는 API를 정의한다. [공통 규약·권한·전체 목록](00-common.md), [MCP 도구](02-mcp-tools.md), [AI 내부 API](03-ai-internal.md)를 함께 참조한다. 경로의 `{id}`는 프로젝트 경로에서는 프로젝트 ID, 그 밖에는 해당 리소스 ID다.

각 절은 요청·응답·오류 예시와 처리 조건을 정의한다. 중첩 DTO의 필드·조건은 타입 링크의 공통 스키마를 따른다. 예제의 계정·ID·해시는 설명용 값이며 실제 비밀정보가 아니다.

## 탐색

- [ACC-A01 회원가입](#acc-a01)
- [ACC-A02 로그인](#acc-a02)
- [ACC-A03 로그아웃](#acc-a03)
- [ACC-A04 내 계정 조회](#acc-a04)
- [ACC-A05 프로필 수정](#acc-a05)
- [ACC-A06 비밀번호 변경](#acc-a06)
- [ACC-A07 비밀번호 재설정 메일 요청](#acc-a07)
- [ACC-A08 재설정 링크 확인](#acc-a08)
- [ACC-A09 비밀번호 재설정](#acc-a09)
- [ACC-A10 회원 탈퇴](#acc-a10)
- [PRJ-A01 프로젝트 생성](#prj-a01)
- [PRJ-A02 참여 프로젝트 목록](#prj-a02)
- [PRJ-A03 프로젝트 상세](#prj-a03)
- [PRJ-A04 프로젝트 정보 수정](#prj-a04)
- [PRJ-A05 프로젝트 삭제](#prj-a05)
- [PRJ-A06 초기 설정 현황](#prj-a06)
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
- [PLN-A10 최초 기준 설계 생성](#pln-a10)
- [DOC-A08 현재 기준 설계 조회](#doc-a08)
- [DOC-A09 기준 설계 이력 목록](#doc-a09)
- [DOC-A10 기준 설계 특정 버전 조회](#doc-a10)
- [DOC-A11 문서·설계 검색](#doc-a11)
- [TRC-A01 설계 항목 의존관계 조회](#trc-a01)
- [DOC-A12 변경용 원문 파일 업로드](#doc-a12)
- [DOC-A13 문서 작성 내용 조회](#doc-a13)
- [DOC-A14 문서 작성 내용 수정](#doc-a14)
- [DOC-A15 문서 보완 파일 재업로드](#doc-a15)
- [DOC-A16 문서 비비 검토 요청](#doc-a16)
- [DOC-A17 문서 보완 제안 반영·넘기기](#doc-a17)
- [DOC-A18 문서 팀장 검토 요청·재제출](#doc-a18)
- [DOC-A19 문서 검토 요청 목록](#doc-a19)
- [DOC-A20 문서 검토 제출 상세](#doc-a20)
- [DOC-A21 문서별 승인·반려](#doc-a21)
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
- [MTG-A01 회의 생성](#mtg-a01)
- [MTG-A02 회의 목록](#mtg-a02)
- [MTG-A03 회의 상세](#mtg-a03)
- [MTG-A04 회의 참여 요청](#mtg-a04)
- [MTG-A05 회의 참여 응답](#mtg-a05)
- [MTG-A06 회의 텍스트 기록](#mtg-a06)
- [MTG-A07 비비의 회의 맥락 분석](#mtg-a07)
- [MTG-A08 회의록 생성](#mtg-a08)
- [MTG-A09 회의 결정 기반 문서 수정안](#mtg-a09)
- [MTG-A10 회의 기록 목록](#mtg-a10)
- [MON-D01 문서 승인 현황](#mon-d01)
- [MON-C01 변경 제안 현황](#mon-c01)
- [MON-T01 작업 상태 현황](#mon-t01)
- [MON-V01 검증 상태 현황](#mon-v01)
- [MON-A02 프로젝트 AI 사용량·추정 비용](#mon-a02)
- [MON-A03 작업·설계·검증 관계](#mon-a03)
- [MON-A04 프로젝트 활동 타임라인](#mon-a04)
- [MON-A05 에이전트 현재 작업 현황](#mon-a05)
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
상태: 설계 초안

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
| `password` | `string` | Y | 비밀번호. 정책 Q04 |
| `name` | `string` | Y | 표시 이름 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a02"></a>

## ACC-A02. 로그인

### POST `/api/auth/login`

로그인 기능을 제공한다.

관련 요구사항: ACC-02  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

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
| `current_password` | `string` | Y | 현재 비밀번호 |
| `new_password` | `string` | Y | 새 비밀번호 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a07"></a>

## ACC-A07. 비밀번호 재설정 메일 요청

### POST `/api/auth/password/forgot`

비밀번호 재설정 메일 요청 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

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
| `email` | `string` | Y | 재설정을 요청할 이메일 |

```json
{
  "email": "member@example.com"
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
- 링크는 유효기간과 1회 사용 조건을 가진다. 만료시간·재발송 제한은 Q04다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a08"></a>

## ACC-A08. 재설정 링크 확인

### POST `/api/auth/password/reset/verify`

재설정 링크 확인 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a09"></a>

## ACC-A09. 비밀번호 재설정

### POST `/api/auth/password/reset`

비밀번호 재설정 기능을 제공한다.

관련 요구사항: ACC-03  
담당: BE: 전진 / AI: — / FE: 박민용  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="acc-a10"></a>

## ACC-A10. 회원 탈퇴

### POST `/api/me/withdraw`

회원 탈퇴 기능을 제공한다.

관련 요구사항: ACC-06  
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
| 409 | `project_membership_remaining` | 참여 프로젝트가 남아 있음 |

```json
{
  "error": {
    "code": "invalid_credentials",
    "message": "비밀번호 불일치"
  }
}
```

#### 처리 규칙

- 모든 프로젝트를 나간 후 가능하다. 팀장인 프로젝트는 위임이 선행되어야 한다.
- 계정 개인정보는 제거·익명화하고 토큰 전부 폐기·세션 종료를 처리한다.
- 작업·편집·사용량 이력은 탈퇴한 사용자로 보존한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a01"></a>

## PRJ-A01. 프로젝트 생성

### POST `/api/projects`

프로젝트 생성 기능을 제공한다.

관련 요구사항: PRJ-01, BEE-01  
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
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `name` | `string` | Y | 프로젝트명 |
| `description` | `string` | N | 설명 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a02"></a>

## PRJ-A02. 참여 프로젝트 목록

### GET `/api/projects`

참여 프로젝트 목록 기능을 제공한다.

관련 요구사항: PRJ-03  
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a03"></a>

## PRJ-A03. 프로젝트 상세

### GET `/api/projects/{id}`

프로젝트 상세 기능을 제공한다.

관련 요구사항: PRJ-02  
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
| `id` | `ID` | Y | 프로젝트 ID |
| `name` | `string` | Y | 프로젝트 이름 |
| `description` | `string` | N | 프로젝트 설명 |
| `my_role` | `LEADER\|MEMBER` | Y | 조회자의 프로젝트 역할 |
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a04"></a>

## PRJ-A04. 프로젝트 정보 수정

### PATCH `/api/projects/{id}`

프로젝트 정보 수정 기능을 제공한다.

관련 요구사항: PRJ-02  
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

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `name` | `string` | N | 이름 |
| `description` | `string` | N | 설명 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a05"></a>

## PRJ-A05. 프로젝트 삭제

### DELETE `/api/projects/{id}`

프로젝트 삭제 기능을 제공한다.

관련 요구사항: PRJ-05  
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="prj-a06"></a>

## PRJ-A06. 초기 설정 현황

### GET `/api/projects/{id}/onboarding`

초기 설정 현황 기능을 제공한다.

관련 요구사항: PRJ-04  
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
| `required_documents` | [RequiredDocument[]](00-common.md#schema-requireddocument) | Y | 필수 문서 4종 |
| `baseline_published` | `boolean` | Y | 최초 기준 발행됨 |
| `member_count` | `integer` | Y | 활성 멤버 수 |
| `my_agent_connected` | `boolean` | Y | 본인 연결 관측 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a01"></a>

## MEM-A01. 팀원 초대 생성

### POST `/api/projects/{id}/invitations`

팀원 초대 생성 기능을 제공한다.

관련 요구사항: MEM-01  
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
| `email` | `string` | N | 지정하면 해당 이메일로 초대 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a02"></a>

## MEM-A02. 초대 목록

### GET `/api/projects/{id}/invitations`

초대 목록 기능을 제공한다.

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
상태: 설계 초안

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
| 410 | `invitation_expired` | 만료 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mem-a05"></a>

## MEM-A05. 초대 수락

### POST `/api/invitations/accept`

초대 수락 기능을 제공한다.

관련 요구사항: MEM-02  
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
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `token` | `string` | Y | 초대 토큰 |

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
| 409 | `invitation_used` | 이미 소비된 초대 |
| 409 | `already_member` | 이미 참여 |
| 410 | `invitation_expired` | 만료 |

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
- 사람 세션만 발급한다. `label`은 앞뒤 공백을 빼고 100자까지이며, 없거나 공백뿐이면 이름 없는 토큰(`label` 생략)이다.
- 한 계정이 한 프로젝트에 가진 폐기 안 된 토큰이 5개면 409 `agent_limit_reached`다(Q06 제안값, -175). 다른 팀원의 토큰은 세지 않는다. 폐기하면 다시 발급할 수 있다. 동시에 발급해도 한도를 넘지 않는다.
- 발급한 토큰은 `/mcp`의 `Authorization: Bearer`로 쓴다. 요청마다 토큰 폐기와 현재 멤버십을 확인한다. 없는 토큰은 401 `unauthenticated`, 폐기·제외·나가기·프로젝트 삭제·탈퇴 뒤의 토큰은 401 `token_revoked`다. `last_used_at`은 1분 단위로 갱신한다.

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
| 400 | `validation_failed` | `project_id`·`cursor`가 숫자가 아님 |

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
- 폐기된 토큰도 id 내림차순으로 돌려준다. 폐기 여부는 `revoked_at`으로 구분한다.
- `project_id` 필터는 없는 프로젝트·남의 프로젝트도 오류 없이 0건이다. `project_id`·`cursor`가 숫자가 아니면 400 `validation_failed`다.

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
- 프로젝트를 나간 뒤에도 본인 토큰은 폐기할 수 있다.

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
| 수정 | 최초 baseline 전에는 등록자가 working-copy에서 보완·비비 검토·제출. 제출 원본은 불변. baseline 이후 DOC-A12 원시 DRAFT는 변경 제안 연결 전까지 미제출 |
| 저장 | 로컬 개발·배포 모두 SeaweedFS 비공개 S3 bucket. 로컬은 Docker volume에 영속 저장한다. UUID storage_key 유지, DB에는 원문 텍스트·버전 저장. 경로/자격증명 외부 노출 금지 |
| 다운로드 | 원본 bytes, attachment. 원본명 대신 document-{document_id}-v{version_no}.{md\|txt} 생성 이름 |
| 접수 | 문서/버전·영속 분석 작업·멱등 응답을 같은 DB 트랜잭션으로 저장 후 201/PENDING. AI 완료/승인을 뜻하지 않음 |
| 재시도 | Idempotency-Key 필수. 같은 사용자/프로젝트/동작/키·입력은 24시간 같은 응답, 입력이 다르면 409 idempotency_conflict |

문서 API는 공통 로그인(PR #22)의 세션과 CallerContext로 사용자를 식별하고 현재 멤버십을 검사한다. X-Beez-Account-Id 헤더와 문서 전용 세션 속성으로 인증하지 않는다. 문서 전용 CSRF 토큰은 사용하지 않으며 GET /api/session/csrf는 제공하지 않는다. 초기 업로드는 활성 사람 MEMBER, 분류 변경은 등록자, 실패 분석 재시도는 사람 LEADER다. 문서 작성·비비 검토·문서별 승인 흐름은 [문서 검토 API](01-backend-rest.md#document-review-workflow)을 따른다. 조회/다운로드는 현재 MEMBER다.

문서 변경 요청(DOC-A01·A07·A12, PLN-A02)은 Origin을 먼저 검사하고, 헤더 자체가 없을 때만 Referer의 출처를 검사한다. scheme·host·유효 port는 beez.cors.allowed-origins(CORS_ALLOWED_ORIGINS)의 명시적 HTTP(S) origin과 정확히 일치해야 한다. Origin: null·빈 값·중복·잘못된 형식·미허용 출처는 Referer로 우회하지 못한다. 출처가 둘 다 없거나 검증에 실패하면 403 request_origin_rejected이며 저장·변경을 수행하지 않는다. GET 조회에는 이 검사를 요구하지 않는다. 브라우저가 헤더를 전송하므로 FE가 Origin을 직접 설정하거나 토큰을 선조회하지 않는다.

공통 CORS 처리에서 먼저 차단된 요청도 403이지만 그 응답 본문은 공통 CORS 구현을 따른다. request_origin_rejected는 문서 출처 검사가 반환하는 오류 코드다.

로컬 Swagger에서 변경 요청을 시험할 때는 Swagger 화면의 실제 origin(예: http://localhost:8080)도 CORS_ALLOWED_ORIGINS에 명시해야 한다. curl·통합 테스트 등 브라우저 밖의 클라이언트는 유효 세션 쿠키와 허용된 Origin 또는 Referer를 함께 보낸다. 이는 사용자 인증을 대신하지 않는다. CORS 허용 목록의 와일드카드·경로·접미사 일치는 허용하지 않는다. 공통 로그인·SecurityConfig·다른 도메인의 정책은 이 변경에서 수정하지 않는다.

한도는 413 file_too_large, 미지원 확장자는 415 unsupported_media_type, UTF-8/빈 입력 등은 400 validation_failed다. DOC-A12에도 같은 정책을 적용한다. 프록시 한도는 인프라 담당에게 별도 전달하며 배포 설정은 변경하지 않는다.

### POST `/api/projects/{id}/documents`

초기 문서 업로드 기능을 제공한다.

관련 요구사항: PLN-01, DOC-01  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 설계 초안

#### 인증

`MEMBER (사람)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

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
| 409 | `document_kind_exists` | 동일 종류 등록 제한(Q07) |

```json
{
  "error": {
    "code": "file_too_large",
    "message": "용량 초과"
  }
}
```

#### 처리 규칙

- 현재 팀장이 원문을 등록한다. 업로드 성공은 승인·분석 완료를 뜻하지 않는다.
- document와 DRAFT document_version을 저장하고 분석을 접수한다.
- 파일 형식·용량·중복 등록은 이 절의 확정 업로드 정책을 따른다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a02"></a>

## DOC-A02. 문서 목록

### GET `/api/projects/{id}/documents`

문서 목록 기능을 제공한다.

관련 요구사항: DOC-02  
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
| `kind` | `PLAN\|ERD\|REQUIREMENTS\|API_SPEC` | N | 종류 |
| `domain` | `string` | N | 영역 |
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
| `version_no` | `integer` | N | 명시할 문서 버전. 생략 시 문서별 최신 승인본 (baseline 생성 전에도 조회 가능) |

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
| 409 | `document_not_approved` | 버전을 지정하지 않았고 문서별 승인본이 아직 없음 |
| 404 | `baseline_not_published` | 승인본 조회 시 현재 기준 없음 |

```json
{
  "error": {
    "code": "baseline_not_published",
    "message": "승인본 조회 시 현재 기준 없음"
  }
}
```

문서별 승인본은 개발 기준 baseline과 구분한다. 최초 baseline 이전에도 팀장이 개별 승인한 문서를 조회할 수 있다. 검색·개발 기준은 계속 current_baseline을 따른다.

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
| `from_version` | `integer` | Y | 변경 전 문서 버전 |
| `to_version` | `integer` | Y | 변경 후 문서 버전 |

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

`MEMBER (사람·등록자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

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
| 400 | `validation_failed` | 잘못된 필터·limit·document_version_id 형식, 다른 필터에 재사용한 cursor |
| 409 | `draft_changed` | DRAFT 페이지 사이 전체 초안이 변경됨 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

<a id="design-draft-snapshot"></a>

##### 전체 스냅샷과 커서

PLN-A06의 DRAFT 응답은 필터·페이지와 무관한 프로젝트 전체 `draft_snapshot_hash`를 제공한다. 첫 페이지만 승인하는 것을 막기 위한 계약이다.

해시 입력은 프로젝트·현재 baseline, 문서별 최신 제출 대상의 문서 ID/종류/버전 ID/원본 해시/상태, 모든 활성 DRAFT 항목의 항목 ID/key/kind/버전 ID/schema_version/본문 해시/원문 출처·locator와 working copy의 document_id/revision/status다. 문서 후보는 초기 내부 DRAFT 또는 실제 제출한 DRAFT/APPROVED이며 미연결 DOC-A12 원시 DRAFT는 후보를 바꾸지 않는다. 각각 안정적인 ID 순으로 정렬해 canonical JSON을 해시한다. 보완·반려·문서별 승인·항목 출처 변경·최초 baseline 생성도 스냅샷을 바꾼다. 색인 진행 상태는 해시에 넣지 않고 생성 시 별도 검사한다.

목록 읽기는 repeatable-read로 페이지와 해시를 같은 DB 스냅샷에서 구한다. DRAFT cursor에는 마지막 버전 ID·전체 초안 스냅샷·프로젝트/필터 식별값을 넣는다. 다른 필터로 재사용하면 400 validation_failed, DRAFT 페이지 사이 전체 초안이 바뀌면 409 draft_changed다.

APPROVED 첫 페이지는 현재 baseline을 선택하고, cursor에 그 baseline ID를 고정한다. 다음 페이지는 새 DRAFT나 현재 baseline 변경과 무관하게 처음 선택한 승인 묶음을 조회한다. 고정한 과거 baseline에 속한 항목은 이후 SUPERSEDED/retired 상태가 되어도 그 정확한 버전으로 조회한다. 커서의 프로젝트/필터 및 baseline 소속을 검사하고, 새로 조회하면 현재 baseline부터 시작한다.

목록의 document_version_id 필터는 권한 있는 URL 프로젝트 범위 안에서 적용한다. 유효한 양수 ID가 다른 프로젝트에 속하거나 존재하지 않으면 200/빈 목록이다. 잘못된 ID 형식·음수/0은 400이며 URL 프로젝트 접근 권한 자체가 없으면 404다. 등록에서 출처를 지정하는 참조 검증과 목록 필터를 혼용하지 않는다.


- 페이지 필터에 맞는 항목을 반환한다. 기본 조회는 현재 baseline 항목이다.
- DRAFT 조회에서 kind/source 필터·페이지와 무관한 전체 프로젝트 초안 스냅샷을 제공한다. 최초 발행 시 정확한 전체 문서·항목 ID 집합과 함께 다시 대조한다.
- 해시 대상과 계산 방식은 [전체 스냅샷 규칙](01-backend-rest.md#design-draft-snapshot)을 따른다.

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
| 403 | `request_origin_rejected` | Origin/Referer가 누락되었거나 허용된 출처와 일치하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류, Idempotency-Key 형식 오류 |
| 409 | `baseline_exists` | 이미 최초 발행됨. 이후 변경은 변경 제안 사용 |
| 409 | `idempotency_conflict` | 같은 Idempotency-Key로 다른 입력 전송 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 최초 baseline 생성 전 HUMAN LEADER만 등록한다. 기존 baseline이 있으면 409 baseline_exists다.
- content는 [DesignItemContent v1](00-common.md#schema-designitem-content-v1)을 검증한다. 추가 JSON 필드는 보존한다.
- 프로젝트 잠금 아래 REQ/API/ENT/POL/CON 접두사와 증가 번호로 표시 key를 발급하며 제외한 번호를 재사용하지 않는다.
- 출처 버전은 같은 프로젝트여야 하고 source_location은 버전과 함께만 지정한다. 출처가 없으면 가짜 source를 반환하지 않는다.
- Idempotency-Key와 Origin/Referer 검사를 적용한다. 같은 사용자/프로젝트/동작/키·같은 입력은 24시간 응답을 재사용하고 다른 입력은 409 idempotency_conflict다.

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
| `replace_source` | `boolean` | N | true면 출처 변경. 생략하면 기존 출처 유지 |
| `source_document_version_id` | `ID` | N | 같은 프로젝트의 새 출처. replace_source=true일 때 null이면 제거 |
| `source_location` | `string` | N | 새 출처의 위치. 출처 버전 없이 지정 불가 |
| `expected_draft_snapshot_hash` | `string` | N | 출처 변경 시 필수. 본문이 같은 출처 변경의 경합도 검사 |

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
| 403 | `request_origin_rejected` | Origin/Referer가 누락되었거나 허용된 출처와 일치하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 버전·본문 경합 또는 승인본 수정 |
| 409 | `draft_changed` | 출처 변경의 전체 스냅샷이 오래되었거나 누락됨 |
| 409 | `baseline_exists` | 이미 최초 발행됨. 이후 변경은 변경 제안 사용 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "초안이 변경되었거나 수정할 수 없는 상태입니다. 최신 내용을 확인해 주세요."
  }
}
```

#### 처리 규칙

- 최초 baseline 생성 전 HUMAN LEADER만 수정한다. 기존 baseline이면 409 baseline_exists다.
- DRAFT의 같은 버전 행을 수정하며 expected_version_id/expected_content_hash를 대조한다. canonical JSON은 객체 키를 재귀 정렬하고 배열 순서·문자열 공백을 보존한 UTF-8 SHA-256이다.
- replace_source=true면 같은 프로젝트 출처로 변경하거나 null 버전으로 출처·위치를 제거한다. 생략하면 출처를 유지한다. key는 바뀌지 않는다.
- 출처 변경에는 PLN-A06의 expected_draft_snapshot_hash가 필수다. 본문이 같아도 출처 경합을 감지하고 불일치/누락은 409 draft_changed다.
- content는 [공통 본문 스키마](00-common.md#schema-designitem-content-v1)를 따르며 Origin/Referer를 검사한다.

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
| 403 | `request_origin_rejected` | Origin/Referer가 누락되었거나 허용된 출처와 일치하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 대상 버전 또는 expected_content_hash 누락·형식 오류 |
| 409 | `version_conflict` | 승인본·경합·참조 충돌 |
| 409 | `baseline_exists` | 이미 최초 발행됨. 이후 변경은 변경 제안 사용 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "초안이 변경되었거나 수정할 수 없는 상태입니다. 최신 내용을 확인해 주세요."
  }
}
```

#### 처리 규칙

- 최초 baseline 생성 전 HUMAN LEADER만 제외한다. Origin/Referer·버전·본문 해시를 검사한다. 기존 baseline이면 409 baseline_exists다.
- DRAFT를 REJECTED로 보관하고 기본 DRAFT 목록·baseline 대상에서 제외한다. 복원은 현재 제공하지 않는다.

- 초기 발행 대상에서 초안을 제외한다. 물리 삭제하지 않는다.
- 기존 승인 항목 폐기는 변경 제안의 RETIRE를 사용한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="pln-a10"></a>

## PLN-A10. 최초 기준 설계 생성


### POST `/api/human/baselines`

최초 기준 설계 생성 기능을 제공한다.

관련 요구사항: PLN-08·09, DOC-08  
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
| 403 | `request_origin_rejected` | Origin/Referer가 누락되었거나 허용된 출처와 일치하지 않음 |
| 404 | `not_found` | 프로젝트가 없거나 접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `baseline_exists` | 이미 최초 발행됨 |
| 409 | `draft_changed` | 검토 후 초안 변경 |
| 400 | `required_documents_missing` | 필수 문서 누락 |
| 409 | `document_not_approved` | 대상 문서 또는 작성 건의 문서별 팀장 승인이 완료되지 않음 |
| 409 | `analysis_not_completed` | 필수 문서 4종 중 색인 미준비·실패·불일치 |
| 409 | `idempotency_conflict` | 같은 Idempotency-Key로 다른 입력 전송 |

```json
{
  "error": {
    "code": "baseline_exists",
    "message": "이미 기준 설계가 발행되었습니다. 이후 변경은 변경 제안으로 요청해 주세요."
  }
}
```

#### 처리 규칙

사용자 확정: **문서는 각각 팀장이 승인하고, 별도 버튼으로 최초 baseline을 생성한다. 필수 문서 4종의 색인도 모두 완료돼야 한다.** 수동 항목은 1개 이상이어야 한다. 비비 검토·제안 처리는 문서 제출의 조건이며 자동 항목 추출의 완료는 요구하지 않는다.

1. 현재 사람 팀장 검사 → project 행 잠금 → 현재 권한 재확인.
2. 기존 멱등 응답 재요청이면 같은 결과 반환. 새 요청이면 기존 baseline이 없어야 한다.
3. PLAN/REQUIREMENTS/ERD/API_SPEC 각 1개 최신 제출 대상, 전체 활성 DRAFT 항목의 정확한 ID 집합과 검토 스냅샷을 확인한다. 대상 문서와 working copy는 모두 APPROVED여야 한다. 중복 ID·일부 항목만 제출·검토 후 변경은 허용하지 않는다. 항목 출처가 있으면 baseline 문서 버전 중 하나여야 한다.
   발행 대상 document_version.content_text가 null·빈 문자열·공백뿐이면 400 validation_failed로 거절한다. 업로드 시 이미 검사하지만 직접 적재 등으로 잘못된 DB 상태가 들어오는 경우의 발행 방어 규칙도 유지한다.
4. 각 문서의 processing.status=COMPLETED, source_status/index_status=READY, 현재 attempt/text_hash와 manifest 일치, 현재 embedding profile 일치, manifest 청크 수와 실제 임베딩 보유 청크 수 일치를 검사한다. 미완료·실패·누락·불일치는 409 analysis_not_completed다.
5. 같은 트랜잭션에서 수동 항목 APPROVED·승인 시각, baseline v1·baseline_document·baseline_item, project.current_baseline_id와 멱등 응답을 기록한다. 이미 완료한 문서 승인자/시각/결정은 바꾸지 않는다. 중간 실패는 baseline 생성·항목 전이 전체를 rollback하며 기존 문서 승인은 유지한다.

부족한 필수 문서는 400 required_documents_missing, 문서 미승인은 409 document_not_approved, 기존 baseline은 409 baseline_exists, 전체 집합/해시 불일치는 409 draft_changed다. baseline의 published_by/published_at을 응답의 approved_by/created_at으로 매핑한다. 문서별 승인 이력과 baseline 생성 이력은 별개로 보존된다.

최초 발행에는 과거 baseline이 없으므로 기존 검증을 무효화하지 않는다. 변경 후 승인·발행·재검증은 CHG-A09 계약을 따른다. 타임라인 활동 이벤트 연결은 후속 104 범위로 유지하며 이 PR의 저장 성공을 이벤트 전달 완료로 보지 않는다.

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
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="document-review-workflow"></a>
<a id="document-review-api"></a>

<a id="doc-a13"></a>

## DOC-A13. 문서 작성 내용 조회

### GET `/api/projects/{id}/documents/{document_id}/working-copy`

문서 작성 내용 조회 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`. [DocumentWorkingCopy](00-common.md#schema-documentworkingcopy) 반환.

```json
{
  "document_id": "201",
  "owner_account_id": "11",
  "revision": 1,
  "status": "EDITING",
  "content": "# 기획안\n문서 내용을 보완한다.",
  "file_name": "plan.md",
  "submissions": []
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 같은 프로젝트의 활성 사람 팀원이 작성 내용·마지막 검토·제출 이력을 조회한다. 다른 프로젝트 문서는 404다.
- 작성 건 상태와 문서 버전 상태는 별개다. 아직 제출하지 않은 내용은 승인본이나 baseline으로 취급하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a14"></a>

## DOC-A14. 문서 작성 내용 수정

### PATCH `/api/projects/{id}/documents/{document_id}/working-copy`

문서 작성 내용 수정 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |

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
| `expected_revision` | `integer` | Y | 조회한 양수 작성 리비전 |
| `content` | `string` | Y | 수정할 비어 있지 않은 본문 |
| `file_name` | `string` | Y | md/txt 파일명 |

```json
{
  "expected_revision": 1,
  "content": "# 보완한 기획안",
  "file_name": "plan.md"
}
```

#### Response

HTTP `200`. [DocumentWorkingCopy](00-common.md#schema-documentworkingcopy) 반환.

```json
{
  "document_id": "201",
  "owner_account_id": "11",
  "revision": 2,
  "status": "EDITING",
  "content": "# 보완한 기획안",
  "file_name": "plan.md",
  "submissions": []
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 등록자만 수정한다. 팀장도 다른 작성자의 내용을 대신 수정할 수 없다.
- 프로젝트 잠금 후 권한과 expected_revision을 재확인한다. SUBMITTED이면 내용을 바꿀 수 없다.
- 성공하면 작성 revision을 증가시키고 EDITING으로 저장한다. 직접 편집은 이전 비비 검토를 무효화한다.
- 매 편집마다 정식 문서 버전을 만들지 않는다. 본문은 기존 UTF-8·크기·정규화 규약을 따른다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a15"></a>

## DOC-A15. 문서 보완 파일 재업로드

### POST `/api/projects/{id}/documents/{document_id}/working-copy/file`

문서 보완 파일 재업로드 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | multipart/form-data; boundary는 클라이언트가 설정 |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |

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
| `expected_revision` | `integer` | Y | 조회한 양수 작성 리비전 |
| `file` | `binary` | Y | md/txt 파일 1개 |

#### Response

HTTP `200`. [DocumentWorkingCopy](00-common.md#schema-documentworkingcopy) 반환.

```json
{
  "document_id": "201",
  "owner_account_id": "11",
  "revision": 2,
  "status": "EDITING",
  "content": "# 기획안\n문서 내용을 보완한다.",
  "file_name": "updated.md",
  "submissions": []
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |
| 413 | `file_too_large` | 기존 파일 한도 초과 |
| 415 | `unsupported_media_type` | md/txt 이외 확장자 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 등록자만 작성 중 파일을 바꾼다. 제출 잠금과 revision 검사·직접 편집 이후 재검토 조건은 DOC-A14와 같다.
- DOC-A01과 같은 확장자·UTF-8·크기 제한을 적용한다. 원본 PUT은 DB 트랜잭션 밖에서 수행하고 저장 후 권한/리비전을 다시 검사한다.
- 원본 bytes는 보존한다. 다음 제출 전 내용 편집이 없으면 이 파일 원본을 사용한다. 실패 시 새 원본은 보상 정리한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a16"></a>

## DOC-A16. 문서 비비 검토 요청

### POST `/api/projects/{id}/documents/{document_id}/bee-reviews`

문서 비비 검토 요청 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / AI 박상은 / FE 오서현

상태: BE 구현 반영안 · AI 내부 연동 합의 대기

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |
| Idempotency-Key | 필수. 같은 입력 재요청은 같은 키 사용 |

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
| `expected_revision` | `integer` | Y | 검토할 작성 리비전 |

```json
{
  "expected_revision": 1
}
```

#### Response

HTTP `201`. [DocumentBeeReview](00-common.md#schema-documentbeereview) 반환.

```json
{
  "id": "301",
  "input_revision": 1,
  "resolved_revision": 1,
  "reference_hash": "sha256:example",
  "summary": "검토가 완료되었습니다.",
  "unavailable_references": [
    "API_SPEC"
  ],
  "references": [],
  "suggestions": [],
  "usage": [
    {
      "request_key": "document-review-example-call-1",
      "feature": "BEE_DRAFT",
      "model": "configured-model",
      "latency_ms": 1,
      "failed": false,
      "tokens_known": false
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |
| 409 | `review_in_progress` | 같은 요청 키의 비비 검토가 실행 중 |
| 409 | `idempotency_conflict` | 같은 요청 키로 다른 입력을 보냄 |
| 409 | `review_stale` | 본문 또는 참고 승인본 변경, 제안 원문 불일치 |
| 502 | `invalid_ai_output` | 비비 결과·식별값·실제 호출 사용량 검증 실패 |
| 503 | `review_unavailable` | 비비 호출 장애 또는 실패한 요청 키 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 등록자가 작성 내용에 대한 검토를 요청한다. 원문 읽기와 승인 API 명세서·ERD·요구사항의 비교·보완 제안 생성은 AI 담당 영역이다.
- BE는 대상 본문/revision과 같은 프로젝트의 현재 문서별 승인 버전을 전달한다. 기존 자기 문서의 승인본도 참고할 수 있다. 자료가 없으면 unavailable_references에 표시한다.
- 모델 호출 중 DB 잠금을 유지하지 않는다. 반환 후 현재 권한·작성 revision·참고 승인본을 다시 확인하고 늦은 결과를 채택하지 않는다.
- 문서/account/요청 키의 실행 기록을 저장한다. 같은 입력·완료 키는 결과를 재사용하고 실행 중이면 review_in_progress다. 실패한 키는 자동 재호출하지 않고 새 키로 재시도한다. 10분 지난 실행은 다음 같은 키 요청에서 실패 처리한다.
- 실제 MODEL 결과·usage·대상 식별값을 검증한다. 색인 완료나 템플릿 결과를 검토 완료로 대신하지 않는다.
- AI 내부 경로·입출력은 담당자 합의 대기다. 이 절은 FE→BE의 현재 구현 계약이며 실제 AI 연결 완료를 뜻하지 않는다.

#### 후속 PR에 적용할 검토 정책 — 정책 확정, 계약 확장 미구현

- 승인본만 참고하며 승인본이 없으면 해당 문서 자체 검토를 진행한다. 미승인 문서는 참고 자료로 사용하지 않는다.
- 본문 검토를 완료하고 승인 참고 자료 부족으로 비교하지 못한 경우에는 팀장 검토 요청을 허용한다. 비교하지 못한 단계와 사유를 팀장에게 표시한다. 모델 실패·타임아웃은 제출 가능한 부분 검토 결과로 취급하지 않는다.
- 자동 수정안 PATCH는 반영/넘기기 대상이고, 설명형 의견 COMMENT는 별도 확인 버튼 없이 표시한다. COMMENT 확인 여부로 제출을 차단하지 않는다. 직접 본문을 수정하면 재검토한다.
- 단계별 상태·PATCH/COMMENT 구분·출처 검증·집합 해시의 정확한 요청/응답 및 저장 구조는 후속 PR에서 구현한다. 현재 응답 스키마와 예시는 이 확장을 아직 포함하지 않는다. AI 라우트·별도 usage feature·시간/크기 제한과 공용 AI 명세는 담당자 합의가 남아 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a17"></a>

## DOC-A17. 문서 보완 제안 반영·넘기기

### PATCH `/api/projects/{id}/documents/{document_id}/bee-reviews/{review_id}/suggestions/{suggestion_id}`

문서 보완 제안 반영·넘기기 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `review_id` | `ID` | Y | 비비 검토 ID |
| `suggestion_id` | `ID` | Y | 보완 제안 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_revision` | `integer` | Y | 현재 작성 리비전 |
| `decision` | `APPLY\|SKIP` | Y | 반영하기 또는 넘기기 |

```json
{
  "expected_revision": 1,
  "decision": "SKIP"
}
```

#### Response

HTTP `200`. [DocumentWorkingCopy](00-common.md#schema-documentworkingcopy) 반환.

```json
{
  "document_id": "201",
  "owner_account_id": "11",
  "revision": 1,
  "status": "EDITING",
  "content": "# 기획안\n문서 내용을 보완한다.",
  "file_name": "plan.md",
  "submissions": [],
  "latest_review": {
    "id": "301",
    "input_revision": 1,
    "resolved_revision": 1,
    "reference_hash": "sha256:example",
    "summary": "검토가 완료되었습니다.",
    "unavailable_references": [
      "API_SPEC"
    ],
    "references": [],
    "suggestions": [],
    "usage": [
      {
        "request_key": "document-review-example-call-1",
        "feature": "BEE_DRAFT",
        "model": "configured-model",
        "latency_ms": 1,
        "failed": false,
        "tokens_known": false
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |
| 409 | `review_stale` | 본문 또는 참고 승인본 변경, 제안 원문 불일치 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 작성자만 제안을 선택한다. review와 suggestion은 해당 문서에 속해야 한다.
- 현재 본문·참고 승인본과 검토가 일치해야 한다. 직접 편집/재업로드 또는 참고 승인본 변경 시 재검토한다.
- APPLY는 현재 본문에서 original_text가 유일하게 일치할 때 replacement_text로 바꾸고 작성 revision과 검토 resolved_revision을 갱신한다. 겹친 제안이 일치하지 않으면 review_stale다.
- SKIP은 본문을 바꾸지 않고 선택을 보관한다. 처리한 선택을 다른 결정으로 덮어쓸 수 없다. 모든 제안을 처리한 뒤 다시 고치거나 팀장에게 제출한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a18"></a>

## DOC-A18. 문서 팀장 검토 요청·재제출

### POST `/api/projects/{id}/documents/{document_id}/review-submissions`

문서 팀장 검토 요청·재제출 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |
| Idempotency-Key | 필수. 같은 입력 재요청은 같은 키 사용 |

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
| `expected_revision` | `integer` | Y | 제출할 작성 리비전 |
| `review_id` | `ID` | Y | 해당 내용을 검토한 비비 결과 |

```json
{
  "expected_revision": 1,
  "review_id": "301"
}
```

#### Response

HTTP `201`. [DocumentReviewSubmission](00-common.md#schema-documentreviewsubmission) 반환.

```json
{
  "id": "401",
  "document_id": "201",
  "document_version_id": "211",
  "submitted_by": "11",
  "revision": 1,
  "status": "SUBMITTED",
  "content_hash": "sha256:example",
  "submitted_at": "2026-10-28T06:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |
| 409 | `review_stale` | 본문 또는 참고 승인본 변경, 제안 원문 불일치 |
| 409 | `review_decisions_pending` | 처리하지 않은 보완 제안이 있음 |
| 409 | `idempotency_conflict` | 같은 요청 키로 다른 입력을 보냄 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 작성자만 제출한다. 최신 내용에 대한 유효한 검토가 필요하며 모든 보완 제안을 반영 또는 넘긴 상태여야 한다. 제안이 없어도 실제 검토 완료 결과가 필요하다.
- 본문과 원본을 불변 제출로 저장하고 작성 건을 SUBMITTED로 잠근다. 첫 제출은 준비한 내부 DRAFT v1을 고정하고 이후 제출은 새 document_version/submission을 만든다.
- 원본 PUT은 트랜잭션 밖에서, 문서 버전·분석 접수·제출·잠금·멱등 응답은 한 트랜잭션에서 저장한다. 원본 보존 규칙은 DOC-A01과 ERD를 따른다.
- 같은 작성자/프로젝트/동작/키·입력은 document_http_command의 24시간 응답을 재사용한다. 제출 이력은 캐시 만료로 삭제하지 않는다.
- 반려 후 같은 document_id의 작성 건을 보완·재검토해 새 제출로 요청한다. 이전 제출·반려 사유는 보존한다. 이미 승인한 내용을 수정 없이 중복 제출할 수 없다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a19"></a>

## DOC-A19. 문서 검토 요청 목록

### GET `/api/projects/{id}/document-review-submissions`

문서 검토 요청 목록 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `SUBMITTED\|APPROVED\|REJECTED` | N | 심사 상태 필터. 생략하면 전체 |

#### Request

없음.

#### Response

HTTP `200`. [DocumentReviewSubmission[]](00-common.md#schema-documentreviewsubmission) 반환.

```json
[
  {
    "id": "401",
    "document_id": "201",
    "document_version_id": "211",
    "submitted_by": "11",
    "revision": 1,
    "status": "SUBMITTED",
    "content_hash": "sha256:example",
    "submitted_at": "2026-10-28T06:00:00Z"
  }
]
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 같은 프로젝트의 활성 사람 팀원이 제출 목록을 조회한다. 팀장 대기 화면은 status=SUBMITTED를 사용한다.
- 현재 구현은 제출 ID 내림차순 배열을 반환하며 페이지 매개변수는 제공하지 않는다. 다른 프로젝트의 제출은 포함하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a20"></a>

## DOC-A20. 문서 검토 제출 상세

### GET `/api/projects/{id}/documents/{document_id}/review-submissions/{submission_id}`

문서 검토 제출 상세 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`MEMBER (사람)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `submission_id` | `ID` | Y | 제출 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`. [DocumentReviewSubmissionDetail](00-common.md#schema-documentreviewsubmissiondetail) 반환.

```json
{
  "submission": {
    "id": "401",
    "document_id": "201",
    "document_version_id": "211",
    "submitted_by": "11",
    "revision": 1,
    "status": "SUBMITTED",
    "content_hash": "sha256:example",
    "submitted_at": "2026-10-28T06:00:00Z"
  },
  "content": "# 기획안\n문서 내용을 보완한다.",
  "review": {
    "id": "301",
    "input_revision": 1,
    "resolved_revision": 1,
    "reference_hash": "sha256:example",
    "summary": "검토가 완료되었습니다.",
    "unavailable_references": [
      "API_SPEC"
    ],
    "references": [],
    "suggestions": [],
    "usage": [
      {
        "request_key": "document-review-example-call-1",
        "feature": "BEE_DRAFT",
        "model": "configured-model",
        "latency_ms": 1,
        "failed": false,
        "tokens_known": false
      }
    ]
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 제출 당시 고정한 본문과 그 제출에 연결한 검토·참고 자료를 조회한다. 이후 working copy의 내용이나 마지막 검토로 대체하지 않는다.
- 같은 프로젝트의 활성 사람 팀원이 조회한다. 문서/제출 소속이 다르면 404다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a21"></a>

## DOC-A21. 문서별 승인·반려

### POST `/api/projects/{id}/documents/{document_id}/review-submissions/{submission_id}/decision`

문서별 승인·반려 기능을 제공한다.

관련 요구사항: 문서 검토·보완 및 승인 흐름

담당: BE 오서현 / FE 오서현

상태: BE 구현 반영안

#### 인증

`LEADER (사람)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 기존 허용 출처 검사 적용 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `document_id` | `ID` | Y | 문서 ID |
| `submission_id` | `ID` | Y | 제출 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `decision` | `APPROVE\|REJECT` | Y | 팀장 심사 결과 |
| `rejection_reason` | `string` | N | REJECT이면 필수. 비어 있지 않은 10,000자 이하 사유 |

```json
{
  "decision": "REJECT",
  "rejection_reason": "프로젝트 삭제 권한을 보완해 주세요."
}
```

#### Response

HTTP `200`. [DocumentReviewSubmission](00-common.md#schema-documentreviewsubmission) 반환.

```json
{
  "id": "401",
  "document_id": "201",
  "document_version_id": "211",
  "submitted_by": "11",
  "revision": 1,
  "status": "REJECTED",
  "content_hash": "sha256:example",
  "submitted_at": "2026-10-28T06:00:00Z",
  "decided_by": "12",
  "decided_at": "2026-10-28T06:05:00Z",
  "rejection_reason": "프로젝트 삭제 권한을 보완해 주세요."
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자·팀장 조건 불충족 |
| 403 | `request_origin_rejected` | 요청 출처 누락·불일치 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안/제출 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래되었거나 수정 없이 중복 제출함 |
| 409 | `submission_decided` | 종결한 제출의 결정을 변경하려 함 |
| 409 | `change_proposal_required` | baseline 이후 변경 제안 경로가 필요함 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 현재 사람 팀장만 심사한다. 잠금 대기 후에도 활성 멤버십·팀장 권한과 정확한 제출을 다시 검사한다.
- SUBMITTED 내용만 심사한다. 문서 버전/제출 결정/작성 상태를 한 트랜잭션에 저장한다. 작성자 알림은 공통 서비스 연동 TODO다.
- APPROVE는 해당 제출 버전을 승인하고 기존 승인 버전이 있으면 SUPERSEDED로 보관한다. 문서별 승인으로 baseline을 자동 생성하지 않는다.
- REJECT는 사유가 필수다. 같은 작성 건을 다시 열고 revision을 증가시켜 이전 검토를 무효화한다. 작성자가 DOC-A14~18로 보완·재검토·재제출한다.
- 같은 결정·사유 재요청은 기존 결과를 반환한다. 종결한 제출을 다른 결정으로 뒤집지 않는다. 반려 사유는 이전 제출에 그대로 남는다.
- 승인·반려 결과와 반려 사유는 제출 이력에 보존하며 DOC-A19·A20으로 조회한다. 알림 전달은 전진 담당 공통 알림 서비스 계약 확정 후 연동한다(TODO). 현재 문서 도메인은 notification에 직접 저장하거나 전용 알림 조회 API를 제공하지 않는다. 최초 baseline은 모든 문서의 승인·색인 완료 후 PLN-A10의 별도 버튼으로 생성한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a01"></a>

## CHG-A01. 설계 변경 제안 생성

### POST `/api/projects/{id}/change-proposals`

설계 변경 제안 생성 기능을 제공한다.

관련 요구사항: CHG-01·02  
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
| 409 | `baseline_conflict` | 기준 baseline이 현재와 다름 |

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a02"></a>

## CHG-A02. 설계 변경 제안 목록

### GET `/api/projects/{id}/change-proposals`

설계 변경 제안 목록 기능을 제공한다.

관련 요구사항: CHG-07  
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

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED` | N | 상태 필터 |
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a03"></a>

## CHG-A03. 설계 변경 제안 상세·비교

### GET `/api/projects/{id}/change-proposals/{proposal_id}`

설계 변경 제안 상세·비교 기능을 제공한다.

관련 요구사항: CHG-04·07  
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
| `proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | Y | 제안 |
| `comparisons` | [Comparison[]](00-common.md#schema-comparison) | Y | 기준·현재·수정안 비교 |

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

<a id="tsk-a01"></a>

## TSK-A01. 작업 계획 등록

### POST `/api/projects/{id}/tasks`

작업 계획 등록 기능을 제공한다.

관련 요구사항: TSK-01, MCP-10  
담당: BE: 변준성 / AI: — / FE: 박민용  
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
상태: 서버 연결 담당 검토

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

<a id="mtg-a01"></a>

## MTG-A01. 회의 생성

### POST `/api/projects/{id}/meetings`

회의 생성 기능을 제공한다.

관련 요구사항: MTG-04  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a02"></a>

## MTG-A02. 회의 목록

### GET `/api/projects/{id}/meetings`

회의 목록 기능을 제공한다.

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

## MTG-A04. 회의 참여 요청

### POST `/api/projects/{id}/meetings/{meeting_id}/participants`

회의 참여 요청 기능을 제공한다.

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a05"></a>

## MTG-A05. 회의 참여 응답

### PATCH `/api/projects/{id}/meetings/{meeting_id}/participation`

회의 참여 응답 기능을 제공한다.

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a06"></a>

## MTG-A06. 회의 텍스트 기록

### POST `/api/projects/{id}/meetings/{meeting_id}/entries`

회의 텍스트 기록 기능을 제공한다.

관련 요구사항: MTG-06·08  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a07"></a>

## MTG-A07. 비비의 회의 맥락 분석

### POST `/api/projects/{id}/meetings/{meeting_id}/bee-analysis`

비비의 회의 맥락 분석 기능을 제공한다.

관련 요구사항: MTG-06  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a08"></a>

## MTG-A08. 회의록 생성

### POST `/api/projects/{id}/meetings/{meeting_id}/minutes`

회의록 생성 기능을 제공한다.

관련 요구사항: MTG-08  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a09"></a>

## MTG-A09. 회의 결정 기반 문서 수정안

### POST `/api/projects/{id}/meetings/{meeting_id}/change-drafts`

회의 결정 기반 문서 수정안 기능을 제공한다.

관련 요구사항: MTG-09  
담당: BE: 오서현 / AI: 양지훈(회의 연결) / 박상은(수정안 생성) / FE: 오서현  
상태: 설계 초안

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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a10"></a>

## MTG-A10. 회의 기록 목록

### GET `/api/projects/{id}/meetings/{meeting_id}/entries`

회의 기록 목록 기능을 제공한다.

관련 요구사항: MTG-04·06·08  
담당: BE: 오서현 / AI: — / FE: 오서현  
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
| `type` | `string` | N | 이벤트 유형 |
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

<a id="ntf-a01"></a>

## NTF-A01. 내 알림함

### GET `/api/me/notifications`

내 알림함 기능을 제공한다.

관련 요구사항: NTF-03  
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

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="ntf-a02"></a>

## NTF-A02. 알림 읽음 처리

### POST `/api/me/notifications/read`

알림 읽음 처리 기능을 제공한다.

관련 요구사항: NTF-03  
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
| Idempotency-Key | 변경 요청 재시도 시 같은 키. 공통 5절 |

#### Path Parameters

없음.

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `ids` | `ID[]` | Y | 읽음 처리할 본인 알림 |

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
