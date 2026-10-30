# BEEZ Backend REST API

버전: 0.7 · 갱신일: 2026-10-29 · 개발 계약 초안

2026-10-28: 화면 수정안(v2) 요청을 반영했다(S15P31A108-218). 새 API(ACC-A11~A13, MEM-A11, DOC-A22~A24, CHG-A14·A15, BEE-A03·A04, MTG-A11~A13, MON-A06·A07)와 기존 API에서 바뀐 부분은 '수정안 요청'으로 표시했다. 문서 검토·승인(DOC-A13~A21)과 최초 기준 생성(PLN-A10)은 PR #37 계약을 그대로 두고 화면에 더 필요한 것만 그 절에 붙였다. ACC-A08·A09와 MTG-A01·A05·A07·A08·A09는 폐지 예정이다. MTG-A04는 팀장과 회의 참여자가 관련자를 직접 추가하는 API로 남긴다. 기준 v1 뒤의 문서 변경은 회의 결과를 포함해 모두 변경 제안으로 간다. 미정 정책은 [검토 항목](00-common.md#9-검토-항목) Q14~Q24다.

2026-10-29: 초대 자동 참여를 정했다(Q05). 수락 버튼은 없고, 초대받은 사람이 그 이메일로 로그인·가입할 때 대기 초대가 멤버십이 된다. 이미 로그인한 채 메일 링크를 열면 MEM-A05로 참여한다.

2026-10-29: 회의 참여자 제외(MTG-A14)를 더했다. 참여자를 빼는 것은 팀장만 하고, 추가(MTG-A04)는 지금처럼 팀장과 회의 참여자가 한다.

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
- [TRC-A02 설계 항목 의존관계 이력 조회](#trc-a02)
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
- [DOC-A22 보완 제안에 대해 비비와 대화](#doc-a22)
- [DOC-A23 비비에게 직접 보완 요청](#doc-a23)
- [DOC-A24 팀장 검토 요청 취소](#doc-a24)
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
- [MTG-A04 회의 참여 요청](#mtg-a04)
- [MTG-A05 회의 참여 응답 (폐지 예정)](#mtg-a05)
- [MTG-A06 회의 텍스트 기록](#mtg-a06)
- [MTG-A07 비비의 회의 맥락 분석 (폐지 예정)](#mtg-a07)
- [MTG-A08 회의록 생성 (폐지 예정)](#mtg-a08)
- [MTG-A09 회의 결정 기반 문서 수정안 (폐지 예정)](#mtg-a09)
- [MTG-A10 회의 기록 목록](#mtg-a10)
- [MTG-A11 비비 회의 정리](#mtg-a11)
- [MTG-A12 회의 정리 수정 요청](#mtg-a12)
- [MTG-A13 회의 끝내기](#mtg-a13)
- [MTG-A14 회의 참여자 제외](#mtg-a14)
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
- (수정안 요청 · 자동 참여, Q05 정함) 가입하면 그 이메일로 대기(PENDING) 중인 초대를 멤버십으로 바꾸고 초대의 `accepted_at`을 채운다. 규칙은 ACC-A02 로그인과 같다. 인증한 이메일(verified_token)로만 바꾸므로 ACC-A11·A12와 같이 또는 그 뒤에 배포한다.

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
- (수정안 요청 · 자동 참여, Q05 정함) 로그인에 성공하면 그 계정 이메일(대소문자 무시)로 대기(PENDING) 중인 초대를 멤버십으로 바꾸고 초대의 `accepted_at`을 채운다. 팀장이 제외(MEM-A09)한 멤버십은 MEMBER로 되살린다. 스스로 나간(MEM-A10) 멤버십이 있는 프로젝트의 초대는 바꾸지 않고 대기로 둔다(MEM-A05로만 참여).
- (수정안 요청 · 자동 참여, Q05 정함) 이메일만 보고 참여시키므로 가입 이메일 인증(ACC-A11·A12)과 같이 또는 그 뒤에 배포한다. 인증 없이 남의 이메일로 가입한 계정이 초대를 가져가지 않게 하려는 것이다.

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
| 429 | `rate_limited` | 같은 이메일·IP의 요청 횟수 초과 |

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
| 429 | `rate_limited` | 발송 횟수 초과 |

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
- (수정안 요청) 각 항목에 design·member_count를 넣어 도시 화면이 추가 호출 없이 건물을 그린다. '준비 중'과 '기준 v1'은 기존 current_baseline 유무로 구분한다. 프로젝트 시작은 PLN-A10 최초 기준 생성이며 따로 시작 상태를 두지 않는다. 검색·정렬은 화면에서 처리하고 `q`·`sort` 파라미터는 필요해지면 추가한다. 부지 번호 저장은 Q22다.

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
- (수정안 요청) required_documents[]에 document_id·latest_version_id·working_copy_status·index_ready를 넣어 팀장 시작 가이드가 문서별 검토·승인 단계와 '기준 만들기' 가능 여부를 그린다.
- (수정안 요청) 4종이 모두 승인·색인되면 팀장이 PLN-A10으로 기준 v1을 만든다(Q15). 그 전에는 변경 제안·작업 계획을 받지 않는다(CHG-A01·TSK-A01 baseline_missing).

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
| `results` | `object[]` | N | emails로 요청하면 이메일별 {email, status: INVITED\|ALREADY_MEMBER\|ALREADY_INVITED, invitation_id?}. 가입한 이메일이든 아니든 새 초대는 INVITED다. 이때 invitation·invite_url은 생략. 수정안 요청(Q05) |

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
| 429 | `rate_limited` | 메일 발송 한도 초과 |

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
- (수정안 요청 · 자동 참여, Q05 정함) emails로 요청하면 이메일마다 대기(PENDING) 초대를 만들고 메일을 보낸다. 초대할 때는 멤버십을 만들지 않는다. 초대받은 사람이 그 이메일로 로그인(ACC-A02)·가입(ACC-A01)하거나, 로그인한 채 메일 링크를 열 때(MEM-A05) 만든다. 응답으로 그 이메일의 가입 여부를 알 수 없게 한다. 만료는 두지 않고, 잘못 보낸 초대는 MEM-A03으로 취소한다.
- (수정안 요청) emails로 요청하면 서버가 초대 메일을 보낸다. 메일에는 초대한 사람·프로젝트와 '프로젝트로 가기' 버튼만 있고, 버튼은 프로젝트 주소(`/projects/{id}?invite={token}`)로 간다. 로그인하지 않았으면 로그인·가입을 거쳐 그 프로젝트로 간다. 링크 토큰은 메일에만 담는다. 문구·발신 주소·발송 한도는 Q05다.
- (수정안 요청) 원치 않는 초대로 들어온 사람은 프로젝트 나가기(MEM-A10)로 빠진다. 끝난 멤버십이 있는 사람을 다시 초대하면, 팀장이 제외(MEM-A09)한 사람은 로그인·가입할 때 그 멤버십을 MEMBER로 되살린다. 스스로 나간(MEM-A10) 사람은 로그인만으로 되살리지 않고 메일 링크를 직접 열었을 때(MEM-A05)만 되살린다. 담당 영역·라벨·색상은 비운다.
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
| `status` | `PENDING\|ACCEPTED\|REVOKED\|EXPIRED` | N | 상태 필터. 설정 › 팀의 '아직 로그인 전' 줄은 PENDING(초대받은 사람이 아직 로그인·가입하지 않음). 초대는 만료가 없어 EXPIRED가 생기지 않음. 수정안 요청 |

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
- (수정안 요청) '아직 로그인 전' 줄의 '메일 다시 보내기'는 MEM-A11, '초대 취소'는 MEM-A03이다.

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
- (수정안 요청 · 자동 참여, Q05 정함) 대기 초대를 취소하면 그 이메일로 로그인·가입해도 팀원이 되지 않는다. 잘못 보낸 초대는 이것으로 거둔다.
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
| `expires_at` | `Time` | N | 만료 시각. 초대는 만료가 없어 생략(Q05) |
| `login_required` | `boolean` | Y | 프로젝트를 열려면 로그인 필요 |
| `inviter` | `object` | Y | 초대한 팀장 {name, email}. 수정안 요청 |
| `project_description` | `string` | N | 프로젝트 소개. 화면은 두 줄 넘으면 말줄임. 수정안 요청 |
| `invited_email` | `string` | N | 이메일을 지정한 초대면 그 주소. 수정안 요청 |
| `account_matches` | `boolean` | N | 로그인 상태면 로그인 계정이 초대 대상과 맞는지. 다르면 화면이 프로젝트를 열지 않고 다시 로그인하게 함. 수정안 요청 |

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
- (수정안 요청) 만료도 404로 합쳐 유효하지 않은 초대(기간 지남·취소·프로젝트 삭제·잘못된 링크)를 not-found 결과 하나로 준다. 화면은 원인을 알리지 않고 '페이지를 찾을 수 없어요'를 보인다. 통신 실패만 따로 다시 불러오기를 둔다.
- (수정안 요청 · 자동 참여, Q05 정함) 메일 링크(`/projects/{id}?invite={token}`)를 연 화면이 부른다. 조회만 하고 멤버십은 만들지 않는다. 로그인 상태이고 account_matches=true면 화면이 버튼 없이 바로 MEM-A05를 부르고, 이미 팀원이면 바로 프로젝트를 연다. 로그인할 때 이미 멤버십이 된 초대도 404가 아니라 정상 응답을 준다. 로그아웃 상태면 초대한 사람·프로젝트를 보여 주고 로그인·가입으로 보낸 뒤 이 링크로 돌아온다.

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
- (수정안 요청 · 자동 참여, Q05 정함) 이미 로그인한 채 메일 링크를 열면 화면이 MEM-A04로 account_matches를 확인한 뒤 버튼 없이 이 API를 부른다. 수락 화면은 없다. 스스로 나간 사람이 다시 초대받았을 때는 이 API로만 다시 참여한다.

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
- (수정안 요청 · 자동 참여, Q05 정함) 제외한 사람을 다시 초대하면 그 사람이 로그인·가입할 때 멤버십을 MEMBER로 되살린다(ACC-A02). 서버는 멤버십이 끝난 이유(제외·나가기)를 구분해 둔다.

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
- (수정안 요청 · 자동 참여, Q05 정함) 스스로 나간 멤버십은 다시 초대받아도 로그인만으로 되살리지 않는다. 메일 링크를 직접 열어 MEM-A05로 참여할 때만 되살린다. 서버는 멤버십이 끝난 이유(제외·나가기)를 구분해 둔다.

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
| 429 | `rate_limited` | 발송 한도 초과 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 새 토큰을 만들어 메일을 다시 보낸다. 이전 링크는 바로 무효다. 초대는 만료가 없어 만료를 다시 잡지 않는다(Q05).
- 링크 토큰은 응답에 넣지 않는다. 발송 한도는 Q05다.
- 이메일을 지정한 초대만 다시 보낼 수 있다.

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
상태: 설계 초안 · 수정안 요청 반영

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

- 현재 팀장이 원문을 등록한다. 업로드 성공은 승인·분석 완료를 뜻하지 않는다.
- document와 DRAFT document_version을 저장하고 분석을 접수한다.
- 파일 형식·용량·중복 등록은 이 절의 확정 업로드 정책을 따른다.
- (수정안 요청) 화면은 업로드 직후 DOC-A16 비비 검토를 부른다. 검토 없이 제출(DOC-A18)할 수 없으므로 검토를 건너뛰는 옵션은 없다.
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
- (수정안 요청) IN_PROGRESS는 작성 건(DOC-A13)이 EDITING·SUBMITTED·REJECTED인 문서, CURRENT는 현재 기준에 들어 있는 승인본이다. 승인되면 진행 중에서 빠지고 현재 문서로 옮겨진다.
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
| `compare` | `VERSIONS\|UPLOAD_TO_REQUEST\|REJECTED_TO_REQUEST` | N | 비교 종류. 기본 VERSIONS(from→to). UPLOAD_TO_REQUEST는 처음 올린 원본→지금 작성 내용 또는 제출본, REJECTED_TO_REQUEST는 반려된 제출→다시 낸 제출(DOC-A20의 제출 본문). 수정안 요청 |

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
| 400 | `validation_failed` | 버전 번호가 양수가 아님 |
| 413 | `diff_too_large` | 각 본문 1,048,576자·50,000줄 초과 |
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
- 현재 md/txt 원문은 TEXT로 비교한다. CHG-A03과 같은 줄별 diff·동일 본문·마지막 개행·계산 한도 규약을 사용한다. 기존 원문·본문 해시는 변경하지 않는다.
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

- (수정안 요청) 화면은 공용 문서함에서 필수 4종이 모두 승인·색인되면 팀장에게 '기준 만들기' 버튼을 보이고 이 API를 부른다. 팀원에게는 '팀장이 기준을 만들면 변경 제안과 작업 계획을 시작할 수 있어요'를 보인다.

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
- 새 의미 검색 색인의 `DOCUMENT_CHUNK`는 표시용 `source.location`과 함께 `chunk_key`, 정확한 `source_spans`, 보조 `context_refs`를 반환한다. 이전 색인에서 정확한 범위가 저장되지 않았다면 이 필드는 생략한다. offset은 정규화 원문의 Unicode 코드 포인트 기준이며 끝은 제외한다.
- 검색 실패를 빈 결과로 표시하지 않는다. 유사도와 승인 근거를 구분한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="trc-a01"></a>

## TRC-A01. 설계 항목 의존관계 조회

### GET `/api/projects/{id}/design-items/{item_id}/dependencies`

설계 항목 의존관계 조회 기능을 제공한다.

관련 요구사항: DOC-13, TRC-01·06  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: BE 구현 반영안 (S15P31A108-167)

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
| `relation_set_revision` | `integer` | Y | 조회 시점의 관계 집합 리비전. 관계 변경이 한 번도 없으면 0 |
| `items` | [DesignDependency[]](00-common.md#schema-designdependency) | Y | 이 항목이 한쪽 끝인 확정·재확인 필요 관계 (`from`, `to` 순) |
| `candidates` | [DependencyCandidate[]](00-common.md#schema-dependencycandidate) | Y | 이 항목이 한쪽 끝인 미확정 후보 |

```json
{
  "relation_set_revision": 3,
  "items": [
    {
      "from_design_item_id": "301",
      "to_design_item_id": "302",
      "relation": "DEPENDS_ON",
      "origin": "EXPLICIT",
      "status": "CONFIRMED",
      "reason": "로그인 API 의 토큰 만료가 인증 정책을 따른다",
      "confirmed_by": "11",
      "confirmed_at": "2026-10-29T09:00:00Z"
    }
  ],
  "candidates": [
    {
      "from_design_item_id": "303",
      "to_design_item_id": "301",
      "reason": "동일 데이터 필드를 사용합니다.",
      "confirmed": false
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | `item_id` 형식 오류 |
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
- 양방향을 모두 준다: 이 항목이 의존하는 관계(`from` = 이 항목)와 이 항목에 의존하는 관계(`to` = 이 항목).
- `items`는 `CONFIRMED`(확정)와 `NEEDS_RECHECK`(연결 항목이 바뀌어 팀장 재확인 필요)다. 재확인 필요는 규칙의 확정 근거로 쓰지 않는다. 해제(`RELEASED`)·거절(`REJECTED`)은 목록에 없고 [TRC-A02](#trc-a02) 이력에만 남는다.
- `items`와 `candidates`는 한 번의 조회(같은 시점)로 읽어 나눈다. 그 사이 후보가 확정돼 두 목록 모두에서 빠지는 일이 없다.
- 리비전은 목록보다 먼저 읽는다. 그 사이 변경이 커밋되면 목록이 더 새로울 수는 있지만 리비전보다 오래되지는 않는다.
- 관계 저장·이력·리비전 규칙은 [관계 저장 서비스 계약](../design/09-relation-service-contract.md)을 따른다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="trc-a02"></a>

## TRC-A02. 설계 항목 의존관계 이력 조회

### GET `/api/projects/{id}/design-items/{item_id}/dependencies/history`

설계 항목 의존관계의 과거 근거·수정 이력을 조회한다.

관련 요구사항: TRC-01·06, DOC-13  
담당: BE: 변준성 / AI: — / FE: 박민용  
상태: BE 구현 반영안 (S15P31A108-167)

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

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | `string` | N | 이전 응답의 next_cursor. 최초 요청은 생략 |
| `limit` | `integer` | N | 기본 20, 최대 100 |

#### Request

없음.
#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [DependencyHistory[]](00-common.md#schema-dependencyhistory) | Y | 이 항목이 한쪽 끝인 관계 변경. 최신순 |
| `has_more` | `boolean` | Y | 다음 페이지 여부 |
| `next_cursor` | `string` | N | 다음 페이지 커서 |

```json
{
  "items": [
    {
      "id": "52",
      "from_design_item_id": "301",
      "to_design_item_id": "302",
      "action": "MARK_RECHECK",
      "origin": "EXPLICIT",
      "status": "NEEDS_RECHECK",
      "baseline_id": "8",
      "from_design_item_version_id": "411",
      "to_design_item_version_id": "415",
      "change_proposal_id": "21",
      "relation_set_revision": 4,
      "reason": "정책 변경",
      "actor_account_id": "11",
      "created_at": "2026-10-29T09:30:00Z"
    }
  ],
  "has_more": false
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | `item_id`·`cursor`·`limit` 형식 오류 |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |

```json
{
  "error": {
    "code": "not_found",
    "message": "리소스가 없거나 프로젝트 소속·접근 권한이 없음"
  }
}
```

#### 처리 규칙

- 이력은 불변이다. 한 행 = 관계 한 쌍의 한 번 변경 (확정·재확인 표시·유지·해제·후보 추가·후보 거절).
- 확정·유지 행은 유효 기준과 그 기준의 양 끝 항목 버전, 근거, 확인자·시각을 반드시 가진다. 재확인 표시·해제는 항목이 기준에서 빠졌을 수 있어 버전이 없을 수 있다.
- `relation_set_revision`은 확정 관계 집합을 바꾼 변경이 만든 리비전이다. 후보 추가·거절은 집합을 바꾸지 않아 없다.
- 과거 근거는 이 이력의 기준·항목 버전으로 복원한다. 현재 관계는 [TRC-A01](#trc-a01)에서 본다.

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
- (수정안 요청) 기준 v1 전에는 DOC-A01처럼 DOC-A16 검토 → DOC-A18 제출 → DOC-A21 승인으로 반영한다. 기준 v1 뒤에는 이 API로 올린 DRAFT를 변경 제안(CHG-A01 documents[].proposed_document_version_id)에 연결하고 CHG-A09 승인으로 반영한다(Q14).

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
- (수정안 요청) 기준 v1 전에는 화면이 DOC-A01·A12로 등록한 직후 이 API를 불러 검토를 시작한다. 검토 없이 DOC-A18로 제출할 수 없으므로 검토를 건너뛰는 경로는 없다. 기준 v1 뒤에는 이 API가 409 change_proposal_required이므로, DOC-A12로 올린 DRAFT는 등록 직후 검토하지 않고 변경 제안(CHG-A01 documents[].proposed_document_version_id)에 연결한다.
- (수정안 요청) 검토 창은 'n단계 / 5단계'와 단계별 결과('문단 4개')를 보인다. DocumentBeeReview.steps로 받는다. 창을 닫아도 검토는 이어지고, 끝나면 등록자에게 알린다. 지금처럼 동기 호출이면 화면은 진행률 없이 기다림만 보인다(Q19).
- (수정안 요청) 반려된 뒤의 검토: 서버가 이 작성 건의 가장 최근 제출을 보고, 그 제출이 REJECTED이면 rejection_submission_id로 고른다. 그 rejection_reason과 함께 AI-A10에 mode=RERUN으로 넣고, 아니면 REVIEW다. 화면은 제출을 고르지 않는다. 비비가 사유와 이어진 문단을 찾아 category=REJECTION_FIX 제안을 만들고, 팀장은 문단을 고르지 않는다.

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
| `replacement_text` | `string` | N | APPLY일 때만. 대화(DOC-A22)에서 비비가 낸 다른 대체문으로 반영할 때 그 문장. 생략하면 제안의 replacement_text. 수정안 요청 |

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
- (수정안 요청) 대화(DOC-A22)에서 비비가 다른 대체문(reply.proposed_text)을 내면 APPLY에 `replacement_text`로 그 문장을 보내 반영한다. 서버는 그 제안의 대화에 남은 비비 대체문 가운데 하나와 정확히 같을 때만 받고, 다르면 400 validation_failed다. SKIP에 보내도 400이다. original_text 유일 일치와 review_stale 규칙은 같고, 반영에 쓴 문장은 제안의 결정과 함께 남긴다. 지금 dev 구현은 저장된 replacement_text만 쓰므로 BE 변경이 필요하다.
- (수정안 요청) 화면에는 반영한 문단의 '되돌리기'가 있다. 처리한 선택을 덮어쓰지 않는 지금 규칙과 맞지 않으므로, `decision=REVERT`로 받을지 화면에서 뺄지 정해야 한다.

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
- (수정안 요청) 제출하면 팀장에게 알림을 보내고 팀장의 '지금 할 일'에 '문서 승인 요청'으로 띄운다. 반려 뒤 다시 낸 제출은 submissions 이력으로 알아보고 '반려 사유를 반영해 다시 요청'을 보인다.
- (수정안 요청) 제출 전 최종본 확인 화면은 처음 올린 원본과 지금 작성 내용을 DOC-A06 `compare=UPLOAD_TO_REQUEST`로 비교한다.

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
- (수정안 요청) 승인하면 화면은 완료 화면 없이 목록으로 돌아간다. 승인·반려 알림(DOCUMENT_VERSION)은 작성자에게 꼭 필요해 지금의 알림 TODO를 이 화면 전에 연결해야 한다.
- (수정안 요청) 팀장 화면의 '변경 내용'은 DOC-A06 `compare=REJECTED_TO_REQUEST`(반려된 제출 → 다시 낸 제출) 또는 UPLOAD_TO_REQUEST로 그린다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a22"></a>

## DOC-A22. 보완 제안에 대해 비비와 대화

### POST `/api/projects/{id}/documents/{document_id}/bee-reviews/{review_id}/suggestions/{suggestion_id}/messages`

제안 카드 아래 채팅으로 그 제안에 대해 비비와 상의한다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

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
| `review_id` | `ID` | Y | 비비 검토 결과 ID |
| `suggestion_id` | `ID` | Y | 보완 제안 ID |

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
| `reply` | [ReviewMessage](00-common.md#schema-reviewmessage) | Y | 비비의 답. 다른 대체문이면 proposed_text |

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
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토/제안 소속 불일치 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `review_stale` | 본문 또는 참고 승인본 변경 |
| 502 | `invalid_ai_output` | 비비 결과 검증 실패 |
| 503 | `review_unavailable` | 비비 호출 장애 |

```json
{
  "error": {
    "code": "review_stale",
    "message": "본문 또는 참고 승인본 변경"
  }
}
```

#### 처리 규칙

- 비비가 제안의 이유·근거를 설명하거나 다른 대체문을 낸다(AI-A10 DISCUSS). 본문은 바꾸지 않는다.
- 다른 대체문으로 반영하려면 DOC-A17 APPLY에 replacement_text를 함께 보낸다.
- 대화는 그 제안의 messages에 남고 DOC-A13·A20의 검토 결과로 함께 조회한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a23"></a>

## DOC-A23. 비비에게 직접 보완 요청

### POST `/api/projects/{id}/documents/{document_id}/bee-reviews/{review_id}/requests`

본문 아래 '비비에게 직접 요청하기'로 새 보완 제안을 받는다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review-5582)  
담당: BE: 오서현 / AI: 박상은 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

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
| `review_id` | `ID` | Y | 지금 작성 내용의 비비 검토 결과 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `expected_revision` | `integer` | Y | 지금 작성 리비전 |
| `text` | `string` | Y | 요청 내용 |

```json
{
  "expected_revision": 3,
  "text": "오류 표에 권한 없음(403) 행을 추가해 줘."
}
```

#### Response

HTTP `201`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `suggestion` | [DocumentReviewSuggestion](00-common.md#schema-documentreviewsuggestion) | Y | origin=DIRECT_REQUEST, decision=PENDING인 새 제안 |

```json
{
  "suggestion": {
    "id": "9001",
    "explanation": "요청대로 오류 표에 403 행을 추가합니다.",
    "original_text": "| 401 | `unauthenticated` | 로그인이 필요함 |",
    "replacement_text": "| 401 | `unauthenticated` | 로그인이 필요함 |\n| 403 | `forbidden` | 다른 사람의 알림을 읽으려 함 |",
    "decision": "PENDING",
    "category": "OMISSION",
    "severity": "MINOR",
    "origin": "DIRECT_REQUEST"
  }
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·ID/revision·허용 값 오류 |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/검토 소속 불일치 |
| 409 | `version_conflict` | 작성 revision이 오래됨 |
| 409 | `submission_locked` | 팀장 검토 요청 후 내용이 잠긴 상태 |
| 409 | `review_stale` | 본문 또는 참고 승인본 변경 |
| 502 | `invalid_ai_output` | 비비 결과 검증 실패 |
| 503 | `review_unavailable` | 비비 호출 장애 |

```json
{
  "error": {
    "code": "submission_locked",
    "message": "팀장 검토 요청 후 내용이 잠긴 상태"
  }
}
```

#### 처리 규칙

- 비비가 요청을 반영한 제안 하나를 만들어 같은 검토의 suggestions에 붙인다(AI-A10 REQUEST). original_text는 지금 본문에서 한 곳과만 일치해야 한다(DOC-A17 APPLY와 같은 규칙).
- 새 제안도 반영 또는 넘겨야 DOC-A18로 제출할 수 있다.
- (수정안 요청) replacement_text가 ""이면 original_text를 지운다. 표·문단 옮기기는 옮길 부분부터 도착 위치까지를 한 original_text로 잡아 순서만 바꾼 replacement_text로 낸다(AI-A10).

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="doc-a24"></a>

## DOC-A24. 팀장 검토 요청 취소

### POST `/api/projects/{id}/documents/{document_id}/review-submissions/{submission_id}/withdraw`

팀장이 결정하기 전에 제출을 거두고 작성 내용을 다시 고칠 수 있게 한다.

관련 요구사항: DOC-01 (화면 PNL-02-m-review 최종본 확인)  
담당: BE: 오서현 / FE: 오서현  
상태: 수정안 요청 (협의 필요)

#### 인증

`MEMBER (사람·등록자)`. 현재 프로젝트의 활성 멤버십과 객체 소속을 검사한다.

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
| `submission_id` | `ID` | Y | 제출 ID |

#### Query Parameters

없음.

#### Request

없음.

#### Response

HTTP `200`. [DocumentWorkingCopy](00-common.md#schema-documentworkingcopy) 반환. status=EDITING이다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증 무효 |
| 403 | `forbidden` | 사람·등록자 조건 불충족 |
| 404 | `not_found` | 프로젝트 접근 불가 또는 문서/제출 소속 불일치 |
| 409 | `submission_decided` | 팀장이 이미 승인·반려함 |

```json
{
  "error": {
    "code": "submission_decided",
    "message": "팀장이 이미 승인·반려함"
  }
}
```

#### 처리 규칙

- SUBMITTED인 자기 제출만 거둔다. 제출은 지우지 않고 WITHDRAWN 같은 종결 상태로 이력에 남기며, 작성 건은 EDITING으로 돌아가 revision을 올린다.
- 제출 잠금(DOC-A14 submission_locked)과 맞추려면 제출 상태 값을 하나 더 두어야 한다. 이 API를 둘지, 반려만으로 다시 열지는 오서현과 정한다.
- 팀장에게 간 알림은 남지만 열면 지금 상태를 보인다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a01"></a>

## CHG-A01. 설계 변경 제안 생성

### POST `/api/projects/{id}/change-proposals`

관련 요구사항: CHG-01·02  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: BE 구현, 후속 승인·AI 분석 연동은 197·198

#### 인증

`MEMBER`. 현재 활성 멤버와 같은 프로젝트 소속을 검사한다. 에이전트는 REST 세션을 가장하지 않고 MCP 어댑터에서 같은 업무 서비스를 호출한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 쿠키 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 허용된 화면의 출처 |
| Idempotency-Key | 필수. 제어 문자 없는 1~128자 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

없음.

#### Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `base_baseline_id` | `ID` | Y | 현재 기준 설계 |
| `title` | `string` | Y | 1~200자 제목 |
| `reason` | `string` | Y | 1~10,000자 변경 사유 |
| `task_id` | `ID` | N | 같은 프로젝트의 관련 작업 |
| `validation_id` | `ID` | N | 같은 프로젝트의 검증. task_id 지정 시 해당 작업 소속 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 최대 100개. 원문만 수정하면 빈 배열 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 최대 4개. 항목만 수정하면 빈 배열 |
| `previous_proposal_id` | `ID` | N | 같은 프로젝트에서 본인이 거둔 WITHDRAWN 제안. 새 제안의 연결 정보 |
| `meeting_id` | `ID` | N | 관련 회의. #218 수정안 요청 · 195 미구현 |

```json
{
  "base_baseline_id": "401",
  "title": "로그인 설명 보완",
  "reason": "오류 표시를 명확히 한다.",
  "items": [],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 요구사항\n수정된 로그인 설명"
    }
  ]
}
```

#### Response

HTTP `201`. [ChangeProposal](00-common.md#schema-changeproposal)을 반환한다. 최초 `revision=1`, `status=PROPOSED`이며 `snapshot_hash`는 정확한 제출 스냅샷을 식별한다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 유효한 세션이 없음 |
| 404 | `not_found` | 리소스가 없거나 같은 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 값·본문·버전·키·변경 내용 오류 |
| 400 | `invalid_json` | JSON 역직렬화 오류 |
| 403 | `request_origin_rejected` | 허용된 Origin/Referer가 아님 |
| 403 | `forbidden` | 다른 담당자가 올린 수정본 또는 제안의 변경 요청 |
| 409 | `baseline_missing` | 최초 baseline이 아직 없음 |
| 409 | `baseline_conflict` | 현재 baseline 또는 기준 문서·항목 버전 불일치 |
| 409 | `invalid_transition` | previous_proposal_id가 WITHDRAWN이 아님 |
| 409 | `document_already_proposed` | 업로드 DRAFT가 다른 제안에 연결됨 |
| 409 | `idempotency_conflict` | 같은 요청 키에 다른 입력 |

#### 처리 규칙

- 최초 baseline이 있어야 생성할 수 있다. baseline 전에는 변경 제안이 아니라 초기 문서 보완·제출(문서별 승인 흐름)을 사용한다. 이전 제안 연결은 본인이 거둔 제안에만 허용하며 본문은 새 요청에서 명시한다. REJECTED는 같은 번호에서 재제출한다.
- items/documents 중 기준과 실제로 다른 변경이 하나 이상 필요하다. 원문만 수정할 수 있으며 관련 항목 변경은 선택이다.
- CREATE/UPDATE는 5종 content v1과 schema_version=1을 검증한다. UPDATE/RETIRE의 항목·기준 버전은 해당 baseline에 속해야 한다. 종류 변경과 중복 대상은 거절한다.
- 문서 입력 after_content/proposed_document_version_id는 배타적이다. 업로드 경로는 본인이 올린 DRAFT·동일 문서·동일 parent 기준 버전을 검사한다. DOC-A12 업로드만으로 제출된 것은 아니다.
- after_content로 제출하면(CHG-A01·A04, 같은 서비스를 호출하는 MTG-A13 포함) 서버가 본문을 DOC-A12와 같은 규칙으로 정규화해 원문 저장소에 저장하고, 같은 DB 트랜잭션에서 기준 문서 버전을 부모로 하는 DRAFT 문서 버전과 색인 작업(document_processing, PENDING)을 만들어 제안에 귀속시킨다. 응답과 모든 리비전 조회는 그 버전을 proposed_document_version_id로 항상 돌려준다. 저장소 쓰기는 DB 트랜잭션 밖에서 먼저 하며, 트랜잭션이 실패하거나 멱등 재요청이면 올린 원문을 삭제해 남기지 않는다. 색인 실패의 자동 복구(최대 2회)와 FAILED 재시도(PLN-A02)는 기존 문서와 같고, 색인 완료 확인은 CHG-A09가 한다.
- 제출된 업로드 버전은 제안에 귀속되며 원문·해시·부모·등록자를 변경할 수 없다. 같은 제안의 후속 리비전에서 재사용할 수 있지만 다른 제안에 연결할 수 없다. 제외된 과거 수정본 연결도 이력으로 보존한다. 미연결 DRAFT는 자동 제출·삭제하지 않는다.
- 직접 입력 본문은 비어 있지 않은 100,000자·1 MiB 이내 텍스트이며 저장 전에 BOM 제거와 CR/LF→LF 정규화를 적용한다(DOC-A12와 같다). 정규화한 결과가 기준 본문과 같으면 400 validation_failed다. 변경이 없는 문서는 documents에서 뺀다(불필요한 버전·색인 작업을 만들지 않는다). 원본 파일 업로드도 같은 규약이다. 요청 및 저장 스냅샷 전체의 canonical JSON은 각각 1 MiB 이내다.
- project 행을 잠근 뒤 활성 멤버·계정·토큰을 다시 검사한다. 제안·현재 항목 투영·불변 리비전·문서 연결·멱등 응답을 한 트랜잭션에서 저장한다. 제출만으로 baseline·작업 상태를 바꾸지 않는다.
- CHG-A01/A04/A15는 design_http_command의 24시간 응답 보존을 재사용한다. 프로젝트·계정·동작·사람/에이전트 토큰별 요청 키를 분리하고 재요청 시 현재 권한을 검사한다.
- 제출 후 잠금이다. 승인·검토 시작·반려·발행 명령은 198, 의견·검토 요청은 196, 심층 영향 분석·저장은 188·197이 담당한다. 알림은 공통 서비스 연동 TODO이며 직접 INSERT하지 않는다.
- (#218 수정안 요청 · 195 미구현) 설계 항목은 ChangeItem.proposed_text 문장 하나로 받을 수 있고 구조는 서버가 채운다(Q01). 현재 195는 구조화된 content v1만 받는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a02"></a>

## CHG-A02. 설계 변경 제안 목록

### GET `/api/projects/{id}/change-proposals`

관련 요구사항: CHG-07  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: BE 구현, 후속 승인·AI 분석 연동은 197·198

#### 인증

`MEMBER`. 현재 활성 멤버와 같은 프로젝트 소속을 검사한다. 에이전트는 REST 세션을 가장하지 않고 MCP 어댑터에서 같은 업무 서비스를 호출한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 쿠키 |
| X-Request-Id | 선택. 요청 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `PROPOSED\|IN_REVIEW\|APPROVED\|PUBLISHED\|REJECTED\|WITHDRAWN` | N | 상태 필터 |
| `group` | `IN_PROGRESS\|PUBLISHED\|REJECTED` | N | 목록 탭. IN_PROGRESS는 PROPOSED·IN_REVIEW·APPROVED |
| `scope` | `DESIGN_ITEMS\|DOCUMENTS` | N | 설계 항목 제안만 / 문서 제안만. #218 수정안 요청 · 195 미구현 |
| `q` | `string` | N | 제목·항목 번호 검색어. #218 수정안 요청 · 195 미구현 |
| `sort` | `latest\|status` | N | 최신순(기본)·상태순. #218 수정안 요청 · 195 미구현 |
| `cursor` | `string` | N | 같은 프로젝트·상태·탭의 next_cursor |
| `limit` | `integer` | N | 기본 20, 1~100 |

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `items` | [ChangeProposalSummary[]](00-common.md#schema-changeproposalsummary) | Y | 본문을 제외한 요약 |
| `has_more` | `boolean` | Y | 다음 페이지 여부 |
| `next_cursor` | `string` | N | 다음 페이지 커서 |

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 유효한 세션이 없음 |
| 404 | `not_found` | 리소스가 없거나 같은 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 상태·탭·커서·페이지 크기 오류 |

#### 처리 규칙

- ID 내림차순 keyset 조회다. 본문·diff·영향 후보를 목록마다 읽지 않는다.
- (#218 수정안 요청) 목록은 한 줄 요약이고 상세는 개요·바뀌는 내용·영향·의견 탭에서 CHG-A03·A05·A13으로 읽는다.
- status와 group은 교집합으로 필터하며 일치하지 않으면 0건이다. WITHDRAWN은 전체 또는 status 필터로 조회하고 REJECTED 탭에는 넣지 않는다. 필터는 LIMIT 전에 적용하며 탭 개수 집계는 이 API에서 제공하지 않는다.
- 커서는 프로젝트와 상태·탭 필터에 묶인다. 현재 목록이므로 페이지 사이 상태가 바뀌면 항목 포함 여부가 달라질 수 있다. 승인 시에는 목록 상태가 아니라 검토한 revision/hash를 대조해야 한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a03"></a>

## CHG-A03. 설계 변경 제안 상세·비교

### GET `/api/projects/{id}/change-proposals/{proposal_id}`

관련 요구사항: CHG-04·05·07
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: BE 구현, 후속 승인·AI 분석 연동은 197·198

#### 인증

`MEMBER`. 현재 활성 멤버와 같은 프로젝트 소속을 검사한다. 에이전트는 REST 세션을 가장하지 않고 MCP 어댑터에서 같은 업무 서비스를 호출한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 쿠키 |
| X-Request-Id | 선택. 요청 추적 |

#### Path Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | `ID` | Y | 프로젝트 ID |
| `proposal_id` | `ID` | Y | 변경 제안 ID |

#### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `revision` | `integer` | N | 조회할 제출 리비전. 생략 시 최신 |

#### Request

없음.

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `proposal` | [ChangeProposal](00-common.md#schema-changeproposal) | Y | 선택한 제출 내용·해시와 결정 상태 |
| `comparisons` | [Comparison[]](00-common.md#schema-comparison) | Y | 고정 기준→제안 diff와 현재 승인 내용 |
| `explicit_candidates` | `object[]` | Y | 직접 참조 후보. target_type/target_id/via_design_item_id/assignee_member_id/origin |
| `candidates_truncated` | `boolean` | Y | 후보 500개 초과 여부 |
| `item_texts` | `object[]` | N | 항목별 {design_item_id, before_text, after_text}. 바뀌는 내용 탭의 −/+ 표시. #218 수정안 요청 · 195 미구현 |

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 유효한 세션이 없음 |
| 404 | `not_found` | 리소스가 없거나 같은 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 리비전 입력 오류 |
| 409 | `baseline_conflict` | 저장된 기준 항목을 확인할 수 없음 |
| 413 | `diff_too_large` | 비교 본문 한도 초과 |

#### 처리 규칙

- 선택 리비전의 base_baseline_id와 문서·항목 기준 버전을 고정해 비교한다. 현재 baseline 변경은 requires_refresh, 비교 대상의 현재 본문 변경은 current_changed로 따로 표시한다. diff의 기준을 현재 승인본으로 바꾸지 않는다.
- READ ONLY REPEATABLE READ로 기준·현재·제안을 같은 DB 스냅샷에서 읽는다. 설계 항목 본문은 묶음 조회한다.
- DOC-A06과 같은 줄별 계산기를 사용한다. 동일 본문은 빈 diff. 삭제 줄은 -, 추가 줄은 +, 유지 줄은 공백 접두어다. 마지막 개행 없음은 표준 \ No newline at end of file 표시로 구분하며 비교 중 CR/LF·공백을 정규화하지 않는다.
- 각 본문은 1,048,576자·50,000줄 이내다. 공통 앞뒤 구간을 분리하고 중간 LCS 표가 1,000,000칸을 넘으면 해당 중간 구간을 전체 삭제/추가로 표시한다. 내용은 생략하지 않으며 최소 diff는 보장하지 않는다. JSON 항목은 키를 정렬한 canonical JSON으로 비교한다.
- 후보는 기준 설계 항목의 원문 출처·현재 작업 계획의 직접 참조·검증 참조에 한정한다. 작업은 COMPLETED·CANCELLED를 제외하고(MCP-03 affected_task_ids와 같은 규칙), 검증은 열린 작업의 현재 계획 리비전 검증만 포함한다. API/DATA 계약도 설계 항목으로 표시한다. 확정 설계 관계 1단계+작업 계획 참조 규칙 계산은 관계 서비스 담당과 계약하고, 규칙/AI 후보 저장·조회는 197 후속이다. 후보를 확정 영향·알림·작업 변경으로 사용하지 않는다.
- explicit_candidates는 현재 직접 참조 조회이며 분석 완료 결과가 아니다. MCP-03의 발행당 impact_candidates(최대 10개·잘린 개수)는 197 결과를 쓰는 별도 계약이다. 이 API의 500개/boolean 상한을 그 계약으로 대체하지 않는다. 후보만으로 D5 직접 참조 판정이나 작업 상태를 바꾸지 않는다.
- 과거 리비전의 반려 사유는 그 리비전의 결정에서 조회한다. 현재 baseline과의 비교는 조회 시점 기준이다.
- (#218 수정안 요청 · 195 미구현) 바뀌는 내용 탭은 항목마다 한 줄로 접고, 펼치면 기준 문장과 제안 문장을 −/+로 보인다. 문장은 item_texts에서 읽는다.

### GET `/api/projects/{id}/change-proposals/{proposal_id}/revisions`

동일 인증·경로 권한을 사용하는 CHG-A03의 이력 조회 경로다.

| Query | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `before_revision` | `integer` | N | 이전 next_before_revision. 최초 생략 |
| `limit` | `integer` | N | 기본 20, 1~100 |

HTTP `200`: items 배열의 각 원소는 revision, snapshot_hash, submitted_by, submitted_at, decision, decision_reason, decided_by, decided_at이다. 미결정 필드는 null이다. has_more와 next_before_revision으로 이전 리비전을 조회한다. 오류는 400 validation_failed, 401 unauthenticated, 404 not_found다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a04"></a>

## CHG-A04. 설계 변경 제안 보완·재제출

### PUT `/api/projects/{id}/change-proposals/{proposal_id}`

관련 요구사항: APR-05  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: BE 구현, 후속 승인·AI 분석 연동은 197·198

#### 인증

`MEMBER (제안 계정)`. 현재 활성 멤버와 같은 프로젝트 소속을 검사한다. 에이전트는 REST 세션을 가장하지 않고 MCP 어댑터에서 같은 업무 서비스를 호출한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 쿠키 |
| X-Request-Id | 선택. 요청 추적 |
| Content-Type | application/json |
| Origin 또는 Referer | 허용된 화면의 출처 |
| Idempotency-Key | 필수. 1~128자 |

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
| `base_baseline_id` | `ID` | Y | 현재 기준 설계 |
| `title` | `string` | Y | 1~200자 제목 |
| `reason` | `string` | Y | 1~10,000자 변경 사유 |
| `task_id` | `ID` | N | 같은 프로젝트의 관련 작업 |
| `validation_id` | `ID` | N | 같은 프로젝트의 검증. task_id 지정 시 해당 작업 소속 |
| `items` | [ChangeItem[]](00-common.md#schema-changeitem) | Y | 최대 100개. 원문만 수정하면 빈 배열 |
| `documents` | [DocumentChange[]](00-common.md#schema-documentchange) | Y | 최대 4개. 항목만 수정하면 빈 배열 |
| `expected_revision` | `integer` | Y | 조회한 제출 리비전 번호 |
| `expected_snapshot_hash` | `string` | Y | 조회한 sha256 해시 |

```json
{
  "base_baseline_id": "401",
  "title": "로그인 설명 보완",
  "reason": "오류 표시를 명확히 한다.",
  "items": [],
  "documents": [
    {
      "document_id": "201",
      "base_document_version_id": "211",
      "after_content": "# 요구사항\n수정된 로그인 설명"
    }
  ],
  "expected_revision": 1,
  "expected_snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
}
```

#### Response

HTTP `200`. [ChangeProposal](00-common.md#schema-changeproposal). 같은 id/key를 유지하고 revision을 1 증가시키며 status는 PROPOSED다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 유효한 세션이 없음 |
| 404 | `not_found` | 리소스가 없거나 같은 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 값·본문·버전·키·변경 내용 오류 |
| 400 | `invalid_json` | JSON 역직렬화 오류 |
| 403 | `request_origin_rejected` | 허용된 Origin/Referer가 아님 |
| 403 | `forbidden` | 다른 담당자가 올린 수정본 또는 제안의 변경 요청 |
| 409 | `baseline_missing` | 최초 baseline이 아직 없음 |
| 409 | `baseline_conflict` | 현재 baseline 또는 기준 문서·항목 버전 불일치 |
| 409 | `document_already_proposed` | 업로드 DRAFT가 다른 제안에 연결됨 |
| 409 | `idempotency_conflict` | 같은 요청 키에 다른 입력 |
| 409 | `version_conflict` | 기대 리비전·해시 불일치 |
| 409 | `proposal_locked` | 반려 상태가 아니므로 변경 불가 |
| 409 | `proposal_decision_missing` | 반려 결정 원본이 없음 |

#### 처리 규칙

- REJECTED만 재제출할 수 있다. 재제출하지 않기로 하면 CHG-A15로 거둘 수 있다. PROPOSED·IN_REVIEW·APPROVED·PUBLISHED·WITHDRAWN은 편집 불가다. 같은 제안 계정의 활성 사람/에이전트 호출을 허용하며 실제 제출 토큰도 리비전에 보존한다.
- 이전 제출·반려 결정은 변경하지 않는다. approval은 제안당 1건이 아니라 제출 리비전당 1건이다. 후속 승인 API는 검토한 revision/hash에 고정해야 한다.
- 서버에 별도 편집 중 초안을 저장하지 않는다. 반려 후 보완한 최종 본문 또는 새 업로드 DRAFT를 PUT하면 새 불변 제출 리비전이 된다. after_content로 보완하면 매번 새 DRAFT 문서 버전이 만들어지고 이전 리비전의 버전은 그대로 보존한다.
- previous_proposal_id는 생성 때 정한 연결을 유지하며 PUT 입력에는 지정할 수 없다. 거둔 제안의 수정은 새 제안을 생성해야 한다.
- 그 외 입력·원문 연결·원자성·24시간 멱등성은 CHG-A01과 같다.
- (#218 수정안 요청) 검토 중 다른 제안이 먼저 발행돼 기준이 바뀐(requires_refresh) 제안도 잠겨 있어 제안자가 고칠 수 없다. 팀장이 '기준이 바뀌어 반려'하면 제안자가 CHG-A04로 최신 기준에 맞춰 재제출한다.

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
상태: 구현됨(S15P31A108-198)

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
| `expected_revision` | `integer` | Y | 검토한 제출 리비전 |
| `expected_snapshot_hash` | `string` | Y | 검토한 제출 스냅샷 해시 |

```json
{
  "expected_revision": 1,
  "expected_snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
}
```

#### Response

HTTP `200`. [ChangeProposal](00-common.md#schema-changeproposal)을 래퍼 없이 그대로 반환한다(A01·A04·A15와 같은 모양). `status=IN_REVIEW`인 제안이며, `revision`·`snapshot_hash`는 검토를 시작한 제출본을 가리킨다. 이어서 A09·A10의 `expected_revision`·`expected_snapshot_hash`로 그대로 쓸 수 있다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 사람 세션의 현재 팀장이 아님(일반 팀원·에이전트, 요청 처리 중 팀장 위임 포함) |
| 403 | `request_origin_rejected` | 허용되지 않은 Origin |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | 제안 변경 |
| 409 | `invalid_transition` | 검토 시작 불가 상태 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "제안 변경"
  }
}
```

#### 처리 규칙

- 사람 세션의 현재 LEADER만 검토 상태를 바꾼다. 승인·발행은 별도 동작이다.
- `PROPOSED → IN_REVIEW`만 허용한다. 같은 리비전이 이미 `IN_REVIEW`이면 상태를 바꾸지 않고 성공으로 응답한다. 반려·발행·거둔 제안은 409 `invalid_transition`이다.
- 검토 시작은 선택이다. 승인(A09)·반려(A10)는 `PROPOSED`와 `IN_REVIEW` 모두에서 할 수 있다(00-common Q23).
- `Idempotency-Key`가 필요하다. 같은 키·같은 입력은 24시간 동안 기존 응답을 반환하고 다른 입력은 409 `idempotency_conflict`다(00-common 5절·Q12). 재요청도 현재 팀장 권한을 확인한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a09"></a>

## CHG-A09. 변경 제안 승인·발행

### POST `/api/projects/{id}/change-proposals/{proposal_id}/approve`

변경 제안 승인·발행 기능을 제공한다.

관련 요구사항: APR-01·03~05  
담당: BE: 오서현 / 검증 영향: 변준성 / AI: — / FE: 오서현  
상태: 구현됨(S15P31A108-199·200, 실제 163/197 연결·승인 게이트·알림은 TODO)

#### 인증

`LEADER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Idempotency-Key | 필수. 제어 문자 없는 1~128자, 재시도 시 같은 키. 공통 5절 |

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
| `expected_revision` | `integer` | Y | 검토한 제출 리비전 |
| `expected_snapshot_hash` | `string` | Y | 검토한 제출 스냅샷 해시 |

```json
{
  "expected_base_baseline_id": "401",
  "expected_revision": 1,
  "expected_snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
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
        "proposed_document_version_id": "212",
        "after_content": "# 수정된 요구사항\n팀장이 프로젝트를 삭제한다."
      }
    ],
    "status": "PUBLISHED",
    "revision": 1,
    "snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
    "proposer_account_id": "11",
    "actor_type": "HUMAN",
    "current_baseline_id": "402",
    "requires_refresh": true,
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
| 409 | `version_conflict` | 검토 뒤 제안 변경 |
| 409 | `invalid_transition` | 반려·거둠 등 승인 불가 |
| 403 | `request_origin_rejected` | Origin/Referer가 허용 목록과 다르거나 없음 |
| 409 | `idempotency_conflict` | 같은 키에 다른 입력 |
| 500 | `internal` | 발행 트랜잭션 실패 |
| 409 | `analysis_not_completed` | 제안에 연결한 문서 버전의 색인 미준비·실패·불일치 |

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
- 알림·활동도 같은 발행 트랜잭션 안에서 저장하고 같은 중복 키는 건너뛴다([공통 계약](../design/10-notification-activity-contract.md)). 발행 활동은 CHANGE_PROPOSAL_PUBLISHED, 키는 change-proposal:{id}:published다. 키 충돌 외 저장 오류는 발행도 롤백한다. 재요청은 기존 결과를 반환하며 저장 요청을 반복하지 않는다.
- 같은 사용자·operation·키·입력은 저장한 응답 전체를 24시간 재생한다. 같은 키의 다른 입력은 409 idempotency_conflict다. 재요청도 현재 사람 팀장 권한을 검사한다.
- 다른 키·보존기간 경과 후에도 expected_base_baseline_id·expected_revision·expected_snapshot_hash가 모두 같고 이미 PUBLISHED면 기존 발행 baseline과 현재 제안 조회를 반환한다. 기대값 불일치는 409 version_conflict다. 후속 제안이 발행되어 현재 기준이 달라져도 기존 발행 결과를 반환하며 발행·관계·포트를 다시 실행하지 않는다. 이 분기는 현재 기준 충돌 검사보다 먼저 처리한다. D7 구현 경계는 Q03이다.
- 현재 구현에서 requires_refresh는 제안의 base_baseline_id와 current_baseline_id의 비교값이다. 발행 직후에도 true일 수 있으며 PUBLISHED 재요청의 실패 조건으로 쓰지 않는다.
- 새 항목 버전의 created_by는 현재 제출 리비전의 submitted_by다. submitted_token_id가 있으면 origin=AI_PROPOSED, 없으면 HUMAN이며 승인자는 approval.decided_by·baseline.published_by로 별도 기록한다. 최초 제출과 재제출의 채널이 달라도 현재 리비전 기준을 사용한다.
- UPDATE의 source_document_version_id·source_locator는 기존 근거 원문을 보존한다. 같은 제안에서 문서가 바뀌어도 위치가 새 문서에 유효하다는 보장이 없어 자동 치환하지 않는다. 출처 교체는 명시적 입력·검증 계약을 추가해야 한다.
- 문서별 승인 API는 기준 발행 이후 신규 결정을 change_proposal_required로 거부한다. 이미 완료한 결정의 같은 입력 재요청만 기존 결과를 반환하므로 변경 제안의 문서 승인을 우회하지 않는다.
- 최종 문서·항목 참조를 계산한 뒤 새 기준에 한 번씩 INSERT한다. 이전 기준 참조는 변경하지 않는다. UPDATE 항목 번호는 전체 버전 max+1, CREATE는 1이며 RETIRE는 retired_at을 기록하고 새 기준에서 제외한다. 과거 기준의 참조와 본문은 보존한다.
- 163 연결 전에는 ValidationImpactPort 기본 구현이 no-op + WARN이므로 승인해도 검증 STALE·작업 WAITING은 반영되지 않는다. 197 연결 전 PostPublishAnalysisIntake도 no-op + WARN으로 실제 PENDING 접수행을 만들지 않는다. 실제 어댑터는 발행 트랜잭션에 참여해야 하며 포트 예외·멱등 응답 저장 실패도 전체 롤백한다. 최초 발행(v1) 접수 호출은 이 구현에 포함하지 않고 197에서 추가한다.
- 영향 분석 결과에 따른 승인 게이트는 메서드 하나의 TODO로 현재 항상 통과한다. 같은 발행 트랜잭션 안의 알림·활동 저장 연결도 TODO이며 이 PR의 저장 성공을 알림·활동 저장 완료로 보지 않는다.
- (수정안 요청) 아직 발행하지 않은 제안이 requires_refresh=true(검토 중 다른 제안이 먼저 발행돼 기준이 바뀜)면 409 baseline_conflict로 승인하지 않는다. 팀장은 CHG-A10으로 '기준이 바뀌어 반려'한다.
- (수정안 요청) 제안에 연결한 문서 버전은 DOC-A12로 올릴 때, 또는 after_content로 제출할 때(CHG-A01·A04, MTG-A13 포함) 서버가 원문 저장과 청킹·임베딩 작업(document_processing)을 함께 만든다. 모든 문서 변경에 버전이 있으므로 색인 조건은 한 가지 규칙으로 확인한다. 승인 때 각 버전이 PLN-A10 처리 규칙 4번의 색인 조건(source_status/index_status=READY, attempt/text_hash·manifest·embedding profile 일치, 청크 수 일치)을 갖추지 못했으면 409 analysis_not_completed로 발행하지 않는다. 멈춘 실행은 lease 만료 뒤 자동으로 2번까지 다시 잡고, 실패(FAILED)는 팀장이 PLN-A02로 다시 시도한다. 예전 승인본으로 대신 발행하지 않는다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a10"></a>

## CHG-A10. 변경 제안 반려

### POST `/api/projects/{id}/change-proposals/{proposal_id}/reject`

변경 제안 반려 기능을 제공한다.

관련 요구사항: APR-01·02  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 구현됨(S15P31A108-198)

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
| `expected_revision` | `integer` | Y | 검토한 제출 리비전 |
| `expected_snapshot_hash` | `string` | Y | 검토한 제출 스냅샷 해시 |

```json
{
  "reason": "현재 API 계약과의 차이를 명확하게 작성해 주세요.",
  "expected_revision": 1,
  "expected_snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
}
```

#### Response

HTTP `200`. [ChangeProposal](00-common.md#schema-changeproposal)을 래퍼 없이 그대로 반환한다(A01·A04·A15와 같은 모양). `status=REJECTED`이고 `rejection_reason`이 채워진 제안이다. `revision`·`snapshot_hash`는 반려된 제출본을 가리키며 CHG-A04 재제출의 `expected_revision`·`expected_snapshot_hash`로 쓴다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 사람 세션의 현재 팀장이 아님(일반 팀원·에이전트, 요청 처리 중 팀장 위임 포함) |
| 403 | `request_origin_rejected` | 허용되지 않은 Origin |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 사유 공백·길이(1~10000자) 초과 등 필수 필드·타입·허용 값 오류 |
| 409 | `version_conflict` | `expected_revision`·`expected_snapshot_hash`가 현재 제출본과 다름 |
| 409 | `invalid_transition` | 이미 반려·발행·거둔 제안 |

```json
{
  "error": {
    "code": "version_conflict",
    "message": "제안 리비전이 변경되었습니다. 최신 내용을 조회해 주세요."
  }
}
```

#### 처리 규칙

- 사람 세션의 현재 LEADER만 반려할 수 있다. `PROPOSED`·`IN_REVIEW` 제안만 반려하며 검토 시작(A08)을 거치지 않아도 된다.
- 반려 사유와 검토 이력을 보존한다. 결정 기록(`approval`, 리비전별 1건)과 제안 상태 변경은 한 트랜잭션에서 저장하며, 이 사유가 `rejection_reason`으로 A03·A04에 노출된다. 사유 앞뒤 공백은 제거한다.
- `Idempotency-Key`가 필요하다. 같은 키·같은 입력은 24시간 동안 기존 응답을 반환하고 다른 입력은 409 `idempotency_conflict`다.
- 반려 후에는 같은 제안의 CHG-A04로 새 제출 리비전을 만든다. 이전 내용·반려 사유·결정은 리비전별로 보존하며 덮어쓰지 않는다.
- (#218 수정안 요청) requires_refresh=true 제안도 반려는 항상 가능하며, 승인 대신 반려한다. 화면은 사유에 '기준이 바뀌어 반려'를 미리 채우고, 제안자는 CHG-A04로 최신 기준에 맞춰 재제출한다. 재제출하지 않기로 하면 CHG-A15로 반려된 제안을 거둘 수 있다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a11"></a>

## CHG-A11. 승인 이력 조회

### GET `/api/projects/{id}/change-proposals/{proposal_id}/approval`

승인 이력 조회 기능을 제공한다.

관련 요구사항: APR-04, DOC-09  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: 구현됨(S15P31A108-198)

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

- 실제로 발행(`PUBLISHED`)된 제안에 연결된 승인자·시각·baseline을 조회한다. 발행되지 않았거나 반려·거둔 제안은 승인 이력이 없으므로 404 `not_found`다.
- 반려 이력은 이 API가 아니라 CHG-A03 `/revisions`가 리비전별 결정으로 제공한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a12"></a>

## CHG-A12. 변경 제안 의견·검토 요청 등록

### POST `/api/projects/{id}/change-proposals/{proposal_id}/comments`

변경 제안 의견·검토 요청 등록 기능을 제공한다.

관련 요구사항: APR-02, MTG-04  
담당: BE: 오서현 / AI: 양지훈·박상은 / FE: 오서현  
상태: BE 구현, 알림 전달은 공통 알림 서비스 연동 후

#### 인증

`MEMBER`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 시 발급된 세션 쿠키 |
| Content-Type | application/json |
| X-Request-Id | 선택. 응답·서버 간 호출 추적 |
| Origin 또는 Referer | 허용된 화면 출처 |
| Idempotency-Key | 선택. 있으면 제어 문자 없는 1~128자. 같은 입력 재시도 시 같은 키. 공통 5절 |

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
| `revision` | `integer` | Y | 의견을 쓸 때 제안의 제출 리비전 번호 |
| `author_account_id` | `ID` | Y | 작성 계정 |
| `kind` | `COMMENT\|REVIEW_REQUEST` | Y | 의견 종류 |
| `body` | `string` | Y | 의견 본문 |
| `mentioned_member_ids` | `ID[]` | Y | 지정한 멤버. 없으면 빈 배열 |
| `created_at` | `Time` | Y | 작성 시각 |

```json
{
  "id": "91",
  "proposal_id": "71",
  "revision": 1,
  "author_account_id": "11",
  "kind": "COMMENT",
  "body": "프론트도 이 필드를 사용하겠습니다.",
  "mentioned_member_ids": [],
  "created_at": "2026-10-23T03:00:00Z"
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 400 | `validation_failed` | 필수 필드·타입·허용 값 오류. 본문 공백·4000자 초과, DECISION 등 허용 외 kind, 대상 없는 검토 요청, 활성 멤버가 아닌 대상 |
| 403 | `request_origin_rejected` | Origin/Referer가 누락되었거나 허용된 출처와 일치하지 않음 |
| 409 | `invalid_transition` | 발행되었거나 거둔 제안 |
| 400 | `validation_failed` | Idempotency-Key 형식 오류(1~128자, 제어 문자 금지) |
| 409 | `idempotency_conflict` | 같은 Idempotency-Key 로 다른 내용 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 변경 요청이므로 변경 제안 쓰기와 같이 Origin을 본문 검증보다 먼저 검사한다(위 공통 출처 검사). 믿을 수 없는 Origin 이면 본문이 잘못되어도 403 이다. 단, JSON 으로 읽을 수 없는 본문의 400 은 요청 해석 단계라 Origin 검사보다 먼저일 수 있다(변경 제안 쓰기와 같다).
- 현재 프로젝트 멤버만 의견을 남긴다. 의견 작성은 승인과 다르다. 제안 상태·기준·승인 이력은 바뀌지 않고 DECISION 은 받지 않는다.
- 의견은 작성 시점의 현재 제출 리비전에 귀속된다. 반려 뒤 재제출해도 이전 리비전의 의견은 그대로 남는다. PROPOSED·IN_REVIEW·APPROVED·REJECTED 제안에 쓸 수 있고 PUBLISHED·WITHDRAWN 은 409 이다.
- `mentioned_member_ids`(최대 20)는 이 프로젝트의 현재 활성 멤버여야 한다. 하나라도 아니면 전체를 거절한다. `REVIEW_REQUEST` 는 한 명 이상 필요하다.
- `Idempotency-Key` 는 선택이다. 있을 때만 CHG-A01·A04·A15와 같은 `design_http_command` 에 응답을 저장해 24시간 재사용한다. 범위는 제안·작성 계정·키다. 같은 입력(종류·본문·대상 ID 순서 무관)은 처음 저장한 의견을 그대로 돌려주고 새로 저장하지 않으며, 다른 입력은 409 `idempotency_conflict` 다.
- 같은 키 재요청은 PUBLISHED·WITHDRAWN 검사보다 먼저 재생한다. 의견을 저장한 뒤 제안이 발행되거나 거둬져도 같은 키·같은 입력은 처음 응답을 받고, 새로 저장하는 요청만 409 `invalid_transition` 을 받는다.
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
| `revision` | `integer` | N | 해당 제출 리비전의 의견만. 생략하면 전체 |
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
      "revision": 1,
      "author_account_id": "11",
      "kind": "COMMENT",
      "body": "프론트도 이 필드를 사용하겠습니다.",
      "mentioned_member_ids": [],
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
| 400 | `validation_failed` | cursor·revision 형식 오류 |

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
- 작성 순서(오래된 순)로 반환하고 `cursor` 는 이전 페이지 마지막 의견 ID 다. 새 의견이 추가돼도 다음 페이지가 밀리지 않는다. 모든 제안 상태에서 조회할 수 있다.
- 승인·반려 결정은 목록에 섞지 않는다. 결정 원본은 승인 이력(CHG-A11)이며 `approval` 의 리비전 번호로 의견의 `revision` 과 대응한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a14"></a>

## CHG-A14. 승인 미리 보기

### GET `/api/projects/{id}/change-proposals/{proposal_id}/approval-preview`

승인하면 같이 바뀌는 기준 항목·검증·작업을 저장 없이 미리 계산한다.

관련 요구사항: CHG-05 (화면 PNL-04-v2-approve)  
담당: BE: 오서현 / 검증 영향: 변준성 / AI: — / FE: 오서현  
상태: 구현됨(S15P31A108-199·200, 실제 검증 영향·AI 분석 연결은 TODO)

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
| `baseline_items` | `object[]` | Y | 새 기준에 들어갈 항목 {design_item_id, title, version_no, operation, proposal_item_index}. CREATE의 ID는 null이며 원본 items 배열의 0부터 시작하는 index로 식별 |
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
      "version_no": 2,
      "operation": "UPDATE",
      "proposal_item_index": 0
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
  ],
  "unchanged": {"items": 1, "documents": 4},
  "unreviewed": {"ai_candidates": 0, "open_review_requests": 1}
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 401 | `unauthenticated` | 세션 인증이 유효하지 않음 |
| 403 | `forbidden` | 해당 역할·소유 조건을 충족하지 않음 |
| 404 | `not_found` | 리소스가 없거나 프로젝트 소속·접근 권한이 없음 |
| 409 | `baseline_conflict` | 제안 기준이 현재와 다름(requires_refresh) |
| 400 | `validation_failed` | 잘못된 ID·항목 내용·스키마 |
| 409 | `invalid_transition` | PROPOSED·IN_REVIEW가 아닌 상태 |
| 409 | `version_conflict` | 문서·항목 참조와 제출 리비전 불일치 |
| 409 | `analysis_not_completed` | 제안 문서 버전의 색인 미준비·실패·불일치 |

```json
{
  "error": {
    "code": "unauthenticated",
    "message": "세션 인증이 유효하지 않음"
  }
}
```

#### 처리 규칙

- 사람 팀장만 허용한다. Origin 검사와 Idempotency-Key는 필요하지 않다. REPEATABLE_READ 읽기 전용 트랜잭션에서 CHG-A09와 같은 PublishPlanner로 검증·최종 구성을 계산하며 아무것도 저장하지 않는다. 쓰기 잠금·sequence 발급·관계 apply·멱등 저장도 하지 않는다.
- PROPOSED·IN_REVIEW만 미리 볼 수 있다. 제안 기준이 현재와 다르면 409 baseline_conflict이며 requires_refresh 응답 필드를 별도로 추가하지 않는다. 색인과 참조 검증도 A09와 공유한다.
- baseline_items는 변경 후 최종 항목 목록이다. CREATE는 design_item_id:null, operation:CREATE, proposal_item_index:원본 items 배열의 index, version_no:1이다. UPDATE는 실제 항목 ID와 전체 max+1 번호, UNCHANGED는 기존 번호와 proposal_item_index:null을 반환한다. RETIRE는 제외한다. ID를 미리 발급하거나 예약하지 않는다.
- unchanged는 새 기준에 그대로 참조하는 항목·문서 수다. open_review_requests는 현재 리비전 REVIEW_REQUEST 중 지정 대상 한 명이라도 요청 이후 같은 리비전에 의견을 남기지 않은 요청 건수다. change_comment_target 다중 대상을 사용하고 대상 수가 아닌 요청 수로 세며, 이후 순서는 의견 ID 기준이다.
- 197 연결 전 ai_candidates=0은 호환용 임시값이며 미평가를 뜻한다. 163 연결 전 validations_to_stale·tasks_to_wait는 빈 배열인 기본 구현이며 실제 영향 0건을 확인한 결과가 아니다. PR에서 이 미연결 범위를 반드시 표시한다.
- preview 포트에는 새 기준·버전 ID를 넘기지 않는다. 이전 baseline, 제안 ID·revision/hash, UPDATE·RETIRE의 이전 항목 버전으로 대상을 계산하고 apply 때만 실제 새 ID를 넘긴다.
- 승인 확인 화면에서만 쓴다. 그사이 기준이나 제안이 바뀌면 CHG-A09가 기존 규칙대로 거절하고 화면은 다시 확인하라고 안내한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="chg-a15"></a>

## CHG-A15. 변경 제안 거두기

### POST `/api/projects/{id}/change-proposals/{proposal_id}/withdraw`

관련 요구사항: CHG-01·07 (화면 수정안 연계)
담당: BE: 오서현 / AI: — / FE: 오서현
상태: BE 구현. 공통 알림 연동 TODO

#### 인증

`MEMBER (제안 계정)`. 활성 멤버이며 제안을 작성한 계정만 가능하다. REST는 사람 세션, 에이전트는 별도 MCP 도구 구현 없이 같은 업무 서비스의 권한 검증을 사용한다.

#### Headers

| 헤더 | 값·설명 |
| --- | --- |
| Cookie | 로그인 세션 쿠키 |
| Content-Type | application/json |
| Origin 또는 Referer | 허용된 화면 출처 |
| Idempotency-Key | 필수. 제어 문자 없는 1~128자 |
| X-Request-Id | 선택. 요청 추적 |

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
| `expected_revision` | `integer` | Y | 조회한 양수 제출 리비전 |
| `expected_snapshot_hash` | `string` | Y | 조회한 sha256:소문자 64자리 해시 |

```json
{
  "expected_revision": 1,
  "expected_snapshot_hash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
}
```

#### Response

HTTP `200`. [ChangeProposal](00-common.md#schema-changeproposal)을 래퍼 없이 그대로 반환한다(A01·A04·A08·A10과 같은 모양). status=WITHDRAWN, withdrawn_by/withdrawn_at을 포함하고 revision·snapshot_hash·제출 본문은 유지한다.

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | 필수 값·기대 revision/hash·키 오류 |
| 400 | `invalid_json` | JSON 역직렬화 오류 |
| 401 | `unauthenticated` | 로그인 무효 |
| 403 | `forbidden` | 제안 작성 계정이 아님 |
| 403 | `request_origin_rejected` | 허용된 Origin/Referer가 아님 |
| 404 | `not_found` | 없는 제안·다른 프로젝트·현재 접근 불가 |
| 409 | `version_conflict` | 조회한 revision/hash가 현재와 다름 |
| 409 | `invalid_transition` | 현재 상태가 PROPOSED·IN_REVIEW·REJECTED가 아님 |
| 409 | `idempotency_conflict` | 동일 키에 다른 입력 |

#### 처리 규칙

- 프로젝트 잠금 후 작성자·현재 권한·기대 revision/hash·상태를 검사한다. 승인·발행된 제안은 거둘 수 없다. 반려(REJECTED)된 제안은 같은 번호로 재제출하지 않기로 할 때 거둘 수 있다.
- 상태와 행위자/시각, 24시간 멱등 응답을 한 트랜잭션으로 저장한다. 동일 키·입력은 현재 권한을 확인한 뒤 최초 응답을 재사용한다. 다른 키로 이미 거둔 제안을 다시 거두면 invalid_transition이다.
- 원문·리비전·문서 연결·기존 결정은 삭제하지 않고 baseline·작업 상태를 바꾸지 않는다. WITHDRAWN은 종결 상태이며 다시 작성하려면 새 CHG-A01 요청에 previous_proposal_id를 지정한다. 반려 후 같은 번호 재제출과 구분한다.
- 이전 제안에 귀속된 업로드 DRAFT를 새 제안으로 이동하지 않는다. 새 수정본을 업로드하거나 본문을 명시적으로 입력한다.
- 알림·활동은 공통 서비스 연계 TODO다. 직접 notification INSERT나 발송 성공 응답을 만들지 않는다.
- 화면 수정안의 expected_updated_at 대신 기존 195 검토 식별인 expected_revision/expected_snapshot_hash를 사용한다. FE 연동 시 이 두 필드를 맞춰야 한다. 응답은 A01·A04·A08·A10과 같은 래퍼 없는 ChangeProposal이다.

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
| 409 | `baseline_missing` | 기준 v1 발행 전 |

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
- (수정안 요청) 기준 v1 발행 전에는 409 baseline_missing다. 팀원 시작 가이드와 설계 관계 빈 화면이 이 규칙을 전제로 한다(Q22).

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
- (수정안 요청 · -212, 2026-10-29 변준성 동의) 점유 중 작업은 팀원 제외·나가기(MEM-A09·A10)의 인계와 같은 규칙으로 넘긴다. IN_PROGRESS는 WAITING(REVALIDATION)으로 내리고, 점유 중 작업의 검증(진행 중인 것 포함)은 STALE(`ASSIGNEE_CHANGED`)로 바꾼다. 새 담당자는 새 PASS를 받아 RESUME한다. 완료 보고에서 서버가 접수한 DELTA 검증은 담당자와 무관해 그대로 둔다(PASS면 그대로 완료). 같은 담당자로 바꾸면 상태·검증을 바꾸지 않는다.
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

- 진행 중인 문서 검토(DOC-A16 실행 중)나 OPEN 회의에서 계산한다. 여러 개면 가장 최근 것 하나를 준다.

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
      "status": "JOINED"
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
- (수정안 요청) 사람은 회의를 만들지 않는다. 서버가 작업 충돌을 감지하면 회의를 연다. 기준 v1 뒤에만 연다(작업은 기준 뒤에만 생긴다, TSK-A01 baseline_missing). 기본안: AI-A02 검증에서 담당자가 다른 활성 작업 사이의 BLOCKING CONFLICT가 나오면 origin=BEE_CONFLICT 회의를 만들고, 참여자는 팀장, 그 작업 담당자, 충돌한 설계 항목의 담당 영역 팀원(화면 예: '최팀원 · 화면 문구')이고, 첫 기록은 작업·문서 칩(refs)을 단 비비의 CONFLICT_BRIEF다(Q17).
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
          "status": "JOINED"
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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

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
      "status": "JOINED"
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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a04"></a>

## MTG-A04. 회의 참여 요청

### POST `/api/projects/{id}/meetings/{meeting_id}/participants`

회의 참여 요청 기능을 제공한다. 화면에서는 회의실 참여자 줄의 '관련자 추가'다(수정안 요청).

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: 양지훈 / FE: 오서현  
상태: 수정안 요청

#### 인증

`MEMBER (팀장 또는 회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

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
| `member_ids` | `ID[]` | Y | 추가할 활성 멤버 |
| `reason` | `string` | N | 부르는 이유. 추가된 사람의 알림에 함께 보낸다. 수정안 요청 |

```json
{
  "member_ids": [
    "22"
  ],
  "reason": "초대 링크 오류 응답도 같이 맞춰야 해서 불러요."
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
      "status": "JOINED",
      "role": "LEADER"
    },
    {
      "member_id": "22",
      "status": "JOINED",
      "added_by_member_id": "21"
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
| 409 | `meeting_closed` | 끝난 회의 |
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 비비가 연 회의의 참여자는 팀장과 충돌 작업 담당자로 정해지고 수락 절차 없이 참여한다.
- (수정안 요청) 비비가 부른 사람 말고 더 부를 팀원은 팀장이나 이 회의 참여자가 이 API로 추가한다. 화면은 '관련자 추가' 창이다.
- (수정안 요청) 추가한 사람은 수락 절차 없이 바로 `JOINED`로 참여하고 `target_type=MEETING` 알림을 받는다. `reason`이 있으면 알림에 함께 보낸다.
- (수정안 요청) 참여자에 `added_by_member_id`를 남겨 화면이 '이팀장이 추가'처럼 누가 불렀는지 보여 준다.
- (수정안 요청) 참여자를 빼는 것은 [MTG-A14](#mtg-a14)이고 팀장만 한다. 팀장이 아닌 참여자는 추가만 한다. MTG-A14로 빠진 사람은 이 API로 다시 추가할 수 있다.
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

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
| `status` | `JOINED\|DECLINED` | Y | 참여 의사 |

```json
{
  "status": "JOINED"
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
      "status": "JOINED"
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
- (수정안 요청) 비비가 부른 사람과 관련자 추가(MTG-A04)로 들어온 사람 모두 수락 절차가 없어 이 API를 쓰지 않는다.

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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

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
- (수정안 요청) 이 API 대신 회의 끝내기(MTG-A13)가 회의 결과로 변경 제안을 만든다. 변경 제안과 사람 팀장 승인을 거친다는 위 규칙은 그대로다.

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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- document_changes.after는 회의를 끝내면 만들어지는 변경 제안에 들어갈 문장이고, 근거 발언은 이 회의의 실제 기록 ID만 쓴다.
- 문서와 설계를 직접 바꾸지 않는다. 회의를 끝내면(MTG-A13) 이 정리로 변경 제안을 만든다.
- (수정안 요청) document_changes[].base_version_id는 정리할 때 현재 기준의 그 문서 승인본이다. MTG-A13이 이 값으로 정리 뒤 기준 변경을 확인한다.
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

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
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

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
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a13"></a>

## MTG-A13. 회의 끝내기

### POST `/api/projects/{id}/meetings/{meeting_id}/close`

팀장이 비비 정리를 확인하고 회의를 끝낸다. 회의 결과로 변경 제안이 만들어진다. 제안자는 회의를 끝낸 팀장이고 문안은 비비 정리(MTG-A11)다. 문서와 기준은 팀장이 그 제안을 승인(CHG-A09)할 때 바뀐다.

관련 요구사항: MTG-09 (화면 PNL-06-v2-draft, PNL-06-v2-draft-result)  
담당: BE: 오서현 / AI: 박상은(제안 문안) / FE: 오서현  
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

```json
{
  "summary_id": "951"
}
```

#### Response

HTTP `200`.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `meeting` | [Meeting](00-common.md#schema-meeting) | Y | status=CLOSED인 회의 |
| `change_proposal_id` | `ID` | N | 회의 결과로 만든 변경 제안(제안자 = 회의를 끝낸 팀장). 정리에 바꿀 문서·설계 항목이 없으면 생략 |
| `notified_member_ids` | `ID[]` | Y | 알림을 받은 관련 작업 담당자 |

```json
{
  "meeting": {
    "id": "901",
    "status": "CLOSED",
    "closed_at": "2026-10-28T04:00:00Z"
  },
  "change_proposal_id": "71",
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
| 409 | `summary_outdated` | 정리 뒤 새 기록이 있거나, 최신 정리가 아니거나, 정리에 쓴 문서 버전이 지금 기준과 다름 |
| 409 | `meeting_closed` | 이미 끝난 회의 |
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

```json
{
  "error": {
    "code": "summary_outdated",
    "message": "정리 뒤 새 기록이 있거나 최신 정리가 아님"
  }
}
```

#### 처리 규칙

- 정리의 document_changes와 결정으로 변경 제안을 먼저 만들고, 성공하면 회의를 CLOSED로 바꾼다. 문서 변경이 있는 제안은 원문을 저장소에 올리므로 회의 종료와 제안 생성을 한 DB 트랜잭션으로 묶지 않는다(제안 생성은 외부 트랜잭션 안에서 호출할 수 없다). 제안 생성이 성공한 뒤 회의 종료가 실패해 다시 시도하면 같은 Idempotency-Key로 같은 제안을 돌려받는다(보존 24시간). 제안은 CHG-A01과 같은 계약이며 meeting_id는 이 회의, base_baseline_id는 현재 기준, documents[]는 base_document_version_id와 after_content(document_changes를 적용한 본문)다.
- (수정안 요청) 끝내기 전에 정리의 document_changes[].base_version_id가 지금 기준의 그 문서 승인본과 같은지 다시 확인한다. 정리 뒤 다른 변경 제안이 발행돼 기준이 바뀌었으면 409 summary_outdated로 회의를 끝내지 않는다. 화면은 '정리한 뒤 기준이 바뀌었어요'를 안내하고, 팀장이 비비에게 다시 정리(MTG-A11)를 요청한다. 아직 발행되지 않은 다른 제안은 기준이 아니므로 여기서 막지 않고, 승인할 때 CHG-A09의 requires_refresh 규칙을 따른다.
- 이 API는 문서와 기준을 바꾸지 않는다. 팀장이 CHG-A08 검토와 CHG-A09 승인을 하면 기준 v2, v3이 생기고, 재검증·영향 분석·MCP-03 노출도 CHG-A09 규칙을 따른다.
- 제안자(proposer_account_id)는 회의를 끝낸 팀장이고 actor_type=HUMAN이다. 회의 출처는 meeting_id로 보인다(Q24 정함).
- task_ids의 담당자에게 회의 결과가 변경 제안으로 올라갔다고 알리고 활동에 남긴다.
- 끝난 회의는 읽기만 한다(MTG-A06 409 meeting_closed).
- 회의 결과는 공용 문서함의 비비 검토·제출(DOC-A16·A18)을 거치지 않는다. 비비 회의 정리(MTG-A11)와 변경 제안 검토(CHG-A08)가 검토를 맡는다.
- (수정안 요청) 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mtg-a14"></a>

## MTG-A14. 회의 참여자 제외

### DELETE `/api/projects/{id}/meetings/{meeting_id}/participants/{member_id}`

팀장이 회의 참여자를 뺀다. 화면에서는 회의실 참여자 줄의 '빼기'다(수정안 요청). 참여자 추가는 [MTG-A04](#mtg-a04)다.

관련 요구사항: MTG-05  
담당: BE: 오서현 / AI: — / FE: 오서현  
상태: 수정안 요청

#### 인증

`LEADER (회의 참여자)`. 세션 인증과 역할·소유 조건을 검사한다. 프로젝트 객체는 활성 멤버십과 같은 프로젝트 소속이어야 한다.

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
| `member_id` | `ID` | Y | 뺄 멤버 |

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
| 409 | `leader_not_removable` | 팀장은 회의에서 뺄 수 없음 |
| 409 | `meeting_closed` | 끝난 회의 |
| 409 | `baseline_missing` | 기준 v1 전. 회의실이 닫혀 있음 |

```json
{
  "error": {
    "code": "leader_not_removable",
    "message": "팀장은 회의에서 뺄 수 없음"
  }
}
```

#### 처리 규칙

- (수정안 요청) 참여자는 팀장만 뺀다. 팀원은 이 회의 참여자여도 403 forbidden이다. 추가(MTG-A04)는 지금처럼 팀장과 회의 참여자가 모두 한다.
- (수정안 요청) 팀장 자신은 뺄 수 없어 409 leader_not_removable이다. 비비는 참여자 목록(Participant)에 없으므로 이 API로 뺄 대상이 아니다.
- (수정안 요청) 비비가 부른 사람(작업 충돌 담당자, `role`이 있는 참여자)과 관련자 추가(MTG-A04)로 들어온 사람을 모두 뺄 수 있다.
- (수정안 요청) 빼면 그 참여 행을 지운다. Participant.status에 새 값을 더하지 않고, 빠진 사람은 Meeting.participants에 나오지 않는다. 다시 부르려면 MTG-A04로 추가한다.
- (수정안 요청) 이미 빠졌거나 처음부터 참여자가 아닌 멤버를 다시 부르면 바꾸지 않고 204다(멱등). 이때는 알림과 활동을 남기지 않는다. 이 프로젝트의 멤버가 아니면 404다.
- (수정안 요청) 빠진 사람은 이 회의에 기록(MTG-A06)을 남길 수 없다(403). 프로젝트 멤버이므로 회의 목록·상세·기록 조회(MTG-A02·A03·A10)는 그대로 할 수 있고, 이미 남긴 기록은 지우지 않는다.
- (수정안 요청) 빠진 사람에게 누가 뺐는지 담아 `target_type=MEETING` 알림을 보내고 활동에 남긴다. 알림 대상·내용은 회의 서비스가 정해 공통 알림 서비스로 전달한다.
- (수정안 요청) 비비가 부른 작업 담당자를 빼도, 회의를 끝낼 때 task_ids 담당자에게 가는 알림(MTG-A13)은 그대로 간다. 비비는 회의를 열 때만 참여자를 정하므로 뺀 사람을 같은 회의에 다시 부르지 않는다.
- (수정안 요청) 끝난 회의는 409 meeting_closed이고, 기준 v1 전에는 회의실이 닫혀 있어 409 baseline_missing이다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-d01"></a>

## MON-D01. 문서 승인 현황

### GET `/api/projects/{id}/documents/summary`

문서 승인 현황 기능을 제공한다.

관련 요구사항: MON-01  
담당: BE: 오서현 / AI: — / FE: 박민용  
상태: BE 구현 (S15P31A108-104), 알림·타임라인 이벤트는 별도 PR

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
- (BE 구현, S15P31A108-104) `by_kind`는 PLAN·REQUIREMENTS·API_SPEC·ERD 네 종류를 이 순서로 문서가 없어도 0으로 모두 준다. 종류당 문서가 하나라 각 건수는 0 또는 1이다.
- (BE 구현, S15P31A108-104) `approved_count`는 승인본(APPROVED 버전)이 있는 문서다. 최초 기준 발행 전에 DOC-A21로 승인한 문서도 센다. 기준 발행 뒤에는 승인본이 모두 기준에 들어가므로 기준에 든 문서 수와 같고, 기준 쪽 값은 `baseline`으로 본다. `approved_count`는 승인 여부이며 기준 반영 여부가 아니다. 기준 생성 가능 여부는 `GET /api/projects/{id}/onboarding`의 `required_documents[].index_ready`와 `baseline_published`로 본다. `draft_count`는 미승인 작업이 있는 문서다. 새로 올린 DRAFT 버전이 있거나 작성 건(DOC-A13)이 EDITING·SUBMITTED·REJECTED인 경우이며, 둘이 겹쳐도 한 번만 센다. 제출되어 승인을 기다리는 건도 여기에 포함하고 별도 필드를 두지 않는다.
- (BE 구현, S15P31A108-104) 최초 기준 발행 전에는 `baseline`을 생략한다.

[공통 규약·권한·담당자 목록](00-common.md)

---

<a id="mon-c01"></a>

## MON-C01. 변경 제안 현황

### GET `/api/projects/{id}/change-proposals/summary`

변경 제안 현황 기능을 제공한다.

관련 요구사항: MON-02  
담당: BE: 오서현 / AI: — / FE: 박민용  
상태: BE 구현 (S15P31A108-104), 알림·타임라인 이벤트는 별도 PR

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
- (BE 구현, S15P31A108-104) `by_status`는 PROPOSED·IN_REVIEW·APPROVED·REJECTED·PUBLISHED·WITHDRAWN 여섯 상태를 이 순서로 건수가 0이어도 모두 준다. 거둔(WITHDRAWN) 제안은 REJECTED에 합치지 않고 따로 센다.
- (BE 구현, S15P31A108-104) `review_required_count`는 팀장 결정을 기다리는 PROPOSED와 IN_REVIEW의 합이다. 승인되어 발행을 기다리는 APPROVED는 포함하지 않는다.

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
상태: BE 구현 반영안 (S15P31A108-167)

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
      "confirmed": true,
      "design_item_version_id": "411"
    }
  ]
}
```

#### Error

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `validation_failed` | `task_id`·`design_item_id`가 모두 없음, ID 형식 오류 |
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

- 최소 하나의 기준 ID를 받아 기본 관계를 조회하는 계약이다. 둘 다 주면 두 결과를 합치고 같은 노드·간선은 한 번만 준다.
- 명시 관계와 AI 후보는 confirmed로 구분한다. 과거 관계는 당시 계획·버전과 연결한다.
- 기준이 작업이면: 현재 계획 리비전의 설계 참조(`READS`·`IMPLEMENTS`·`CHANGES`), 작업 간 선후(`BLOCKS`·`RELATES`), 현재 계획 리비전의 가장 최근 검증(`VALIDATED_BY`)과 그 검증이 본 설계 항목·대조한 작업(`COMPARED_WITH`), 이 작업에서 나온 변경 제안(`PROPOSED_FROM`).
- 기준이 설계 항목이면: 설계 의존(`DEPENDS_ON`, 확정·재확인 필요·후보), 현재 계획이 이 항목을 참조하는 작업, 이 항목을 바꾸려는 진행 중 변경 제안(`UPDATE`·`RETIRE`. 새 항목을 만드는 `CREATE`는 아직 항목 번호가 없어 나오지 않음).
- 설계 의존 간선은 `status`를 함께 주고, `confirmed`는 `CONFIRMED`일 때만 true다. 계획·검증의 참조 간선은 그때 본 항목 버전을 `design_item_version_id`로 준다.
- 다른 프로젝트 객체는 번호로도 노출하지 않는다. 관계 변경 이력은 [TRC-A02](#trc-a02)에서 본다.

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
